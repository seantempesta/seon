# Gemini API Capabilities Research

This document details advanced Gemini API features that could be added to the gemini-mcp server to enhance its capabilities beyond basic query, brainstorm, code analysis, and text analysis.

## Current Implementation Analysis

The existing gemini-mcp server at `/Users/sean/src/seon/reference-code/gemini-mcp` uses:

- **SDK**: `@google/genai` version `^0.10.0` (latest is 1.34.0 - update recommended)
- **Models**: `gemini-2.5-pro` and `gemini-2.5-flash`
- **API Method**: `genAI.models.generateContent()` with simple string or formatted contents

The current implementation only passes basic `model` and `contents` parameters to `generateContent()`, missing the `config` parameter that enables advanced features.

---

## 1. Google Search Grounding

### Overview
Enables the model to search the web in real-time to provide up-to-date information, grounding responses in current data rather than training knowledge.

### Supported Models
- Gemini 2.5 Pro, Flash, Flash-Lite
- Gemini 2.0 Flash
- Gemini 1.5 Pro, Flash (uses legacy `google_search_retrieval` tool)

### API Parameters

```typescript
import { GoogleGenAI } from '@google/genai';

const ai = new GoogleGenAI({ apiKey: process.env.GEMINI_API_KEY });

const response = await ai.models.generateContent({
  model: 'gemini-2.5-flash',
  contents: 'Who won the most recent Super Bowl?',
  config: {
    tools: [{ googleSearch: {} }],
  },
});

console.log(response.text);

// Access grounding metadata
const groundingMetadata = response.candidates?.[0]?.groundingMetadata;
if (groundingMetadata) {
  console.log('Search queries:', groundingMetadata.webSearchQueries);
  console.log('Sources:', groundingMetadata.groundingChunks);
}
```

### Response Metadata
When grounding is enabled, responses include `groundingMetadata`:
- `webSearchQueries`: Array of search queries used
- `groundingChunks`: Web sources with URIs and titles
- `groundingSupports`: Links response text segments to source indices
- `searchEntryPoint`: HTML/CSS for Search Suggestions widget (required for display)

### Implementation for gemini-client.ts

```typescript
export async function generateWithGoogleSearch(
  prompt: string,
  model: 'pro' | 'flash' = 'flash'
): Promise<{ text: string; sources: Array<{ uri: string; title: string }> }> {
  const modelName = model === 'pro' ? proModelName : flashModelName;

  const response = await genAI.models.generateContent({
    model: modelName,
    contents: prompt,
    config: {
      tools: [{ googleSearch: {} }],
    },
  });

  const sources = response.candidates?.[0]?.groundingMetadata?.groundingChunks
    ?.map((chunk: any) => ({
      uri: chunk.web?.uri || '',
      title: chunk.web?.title || '',
    }))
    .filter((s: any) => s.uri) || [];

  return {
    text: response.text || '',
    sources,
  };
}
```

### Pricing & Limits
- **Gemini 3**: $14 per 1,000 search queries (billing starts Jan 5, 2026)
- **Gemini 2.5 and older**: $35 per 1,000 prompts (flat rate)
- Free tier includes 500-1,500 requests/day depending on tier
- Can be combined with other tools (code execution, URL context)

### Limitations
- Requires Google Search Suggestions to be enabled
- Best results with temperature of 1.0
- Dynamic retrieval only charges when grounding URLs are present in response

---

## 2. Code Execution

### Overview
Allows the model to write and execute Python code in a secure sandbox, returning both the code and its output. Useful for calculations, data analysis, and generating visualizations.

### Supported Models
- Gemini 2.0 and 2.5 Flash models
- Gemini 3 Flash (with visual thinking for image analysis)
- Gemini 2.5 Pro

### API Parameters

```typescript
const response = await ai.models.generateContent({
  model: 'gemini-2.5-flash',
  contents: 'Calculate the first 20 Fibonacci numbers and plot them',
  config: {
    tools: [{ codeExecution: {} }],
  },
});

// Process response parts
const parts = response.candidates?.[0]?.content?.parts || [];
for (const part of parts) {
  if (part.text) {
    console.log('Explanation:', part.text);
  }
  if (part.executableCode) {
    console.log('Generated code:', part.executableCode.code);
    console.log('Language:', part.executableCode.language);
  }
  if (part.codeExecutionResult) {
    console.log('Output:', part.codeExecutionResult.output);
    console.log('Outcome:', part.codeExecutionResult.outcome);
  }
}
```

### Available Libraries
The sandbox includes 30+ Python libraries:
- **Data**: numpy, pandas, scipy
- **ML**: tensorflow, scikit-learn
- **Visualization**: matplotlib
- **Math**: sympy
- **Other**: opencv-python, and more

### Implementation for gemini-client.ts

```typescript
interface CodeExecutionResult {
  text: string;
  code?: string;
  output?: string;
  outcome?: 'OUTCOME_OK' | 'OUTCOME_FAILED' | 'OUTCOME_DEADLINE_EXCEEDED';
}

export async function generateWithCodeExecution(
  prompt: string
): Promise<CodeExecutionResult> {
  const response = await genAI.models.generateContent({
    model: flashModelName, // Flash models work best
    contents: prompt,
    config: {
      tools: [{ codeExecution: {} }],
    },
  });

  const parts = response.candidates?.[0]?.content?.parts || [];
  const result: CodeExecutionResult = { text: '' };

  for (const part of parts) {
    if (part.text) {
      result.text += part.text;
    }
    if (part.executableCode) {
      result.code = part.executableCode.code;
    }
    if (part.codeExecutionResult) {
      result.output = part.codeExecutionResult.output;
      result.outcome = part.codeExecutionResult.outcome;
    }
  }

  return result;
}
```

### Pricing & Limits
- **No additional charge** beyond standard token pricing
- Maximum runtime: 30 seconds per execution
- Up to 5 executions per request without re-prompting
- Code and output count as output tokens
- Maximum file input limited by token window (~2MB for text files)

### Limitations
- Python only (though model can generate other languages)
- Cannot return media files directly
- May cause regressions in non-code tasks
- No network access from sandbox
- No persistent state between executions

---

## 3. URL Context

### Overview
Allows the model to fetch and analyze content from provided URLs automatically, reducing token usage and eliminating the need to manually fetch and paste content.

### Supported Models
- gemini-2.5-pro
- gemini-2.5-flash
- gemini-2.5-flash-lite
- gemini-live-2.5-flash-preview
- gemini-2.0-flash-live-001

### API Parameters

```typescript
const response = await ai.models.generateContent({
  model: 'gemini-2.5-flash',
  contents: 'Summarize the main points from https://example.com/article',
  config: {
    tools: [{ urlContext: {} }],
  },
});

console.log(response.text);

// Access URL retrieval metadata
const urlMetadata = response.candidates?.[0]?.urlContextMetadata;
if (urlMetadata) {
  console.log('Retrieved URLs:', urlMetadata);
}
```

### Supported Content Types
- **Text**: HTML, JSON, plain text, XML, CSS, JavaScript, CSV, RTF
- **Images**: PNG, JPEG, BMP, WebP
- **Documents**: PDF files

### Implementation for gemini-client.ts

```typescript
interface UrlContextResult {
  text: string;
  urlMetadata?: Array<{
    url: string;
    status: string;
  }>;
}

export async function generateWithUrlContext(
  prompt: string,
  urls?: string[] // Optional: extract from prompt if not provided
): Promise<UrlContextResult> {
  const response = await genAI.models.generateContent({
    model: flashModelName,
    contents: prompt,
    config: {
      tools: [{ urlContext: {} }],
    },
  });

  return {
    text: response.text || '',
    urlMetadata: response.candidates?.[0]?.urlContextMetadata,
  };
}
```

### Pricing & Limits
- **Maximum URLs**: 20 per request
- **Content size limit**: 34MB per URL
- Retrieved content counts toward input token usage
- Two-stage retrieval: indexed content first, live fetch fallback
- **Token savings**: Up to 99.6% reduction compared to manual content inclusion

### Limitations
- URLs must be directly accessible (no login/paywall)
- YouTube videos not supported
- Google Workspace files not supported
- Video/audio files not supported

---

## 4. Function Calling

### Overview
Enables the model to call custom functions you define, returning structured function call requests that your code can execute.

### Supported Models
All Gemini 2.5 and 3 series models

### API Parameters

```typescript
// Define function declarations
const functions = [
  {
    name: 'get_weather',
    description: 'Gets the current weather for a given location',
    parameters: {
      type: 'object',
      properties: {
        location: {
          type: 'string',
          description: 'City and state, e.g., San Francisco, CA',
        },
        unit: {
          type: 'string',
          enum: ['celsius', 'fahrenheit'],
          description: 'Temperature unit',
        },
      },
      required: ['location'],
    },
  },
];

const response = await ai.models.generateContent({
  model: 'gemini-2.5-flash',
  contents: "What's the weather in Tokyo?",
  config: {
    tools: [{ functionDeclarations: functions }],
    toolConfig: {
      functionCallingConfig: {
        mode: 'AUTO', // AUTO, ANY, NONE, or VALIDATED
      },
    },
  },
});

// Check for function calls
if (response.functionCalls && response.functionCalls.length > 0) {
  const call = response.functionCalls[0];
  console.log(`Function: ${call.name}`);
  console.log(`Arguments: ${JSON.stringify(call.args)}`);

  // Execute the function and continue conversation...
}
```

### Function Calling Modes
- **AUTO** (default): Model decides between text response or function call
- **ANY**: Model must predict a function call; supports `allowedFunctionNames`
- **NONE**: Function calling disabled
- **VALIDATED**: Ensures schema adherence with natural language fallback

### Implementation for gemini-client.ts

```typescript
interface FunctionDeclaration {
  name: string;
  description: string;
  parameters: {
    type: 'object';
    properties: Record<string, {
      type: string;
      description?: string;
      enum?: string[];
    }>;
    required?: string[];
  };
}

interface FunctionCallResult {
  text?: string;
  functionCalls?: Array<{
    name: string;
    args: Record<string, any>;
  }>;
}

export async function generateWithFunctions(
  prompt: string,
  functions: FunctionDeclaration[],
  mode: 'AUTO' | 'ANY' | 'NONE' = 'AUTO'
): Promise<FunctionCallResult> {
  const response = await genAI.models.generateContent({
    model: proModelName,
    contents: prompt,
    config: {
      tools: [{ functionDeclarations: functions }],
      toolConfig: {
        functionCallingConfig: { mode },
      },
    },
  });

  return {
    text: response.text,
    functionCalls: response.functionCalls,
  };
}
```

---

## 5. Document/File Analysis

### Overview
Process PDFs, images, and other documents using Gemini's native vision capabilities for text extraction, visual understanding, and structured data extraction.

### Supported Formats
- **Documents**: PDF (up to 50MB, 1000 pages)
- **Images**: PNG, JPEG, WEBP, HEIC, HEIF
- **Text**: TXT, HTML, Markdown (no visual understanding)

### API Parameters - Inline Data

```typescript
import * as fs from 'node:fs';

// For small files (<20MB), use inline data
const pdfBuffer = fs.readFileSync('document.pdf');
const base64Data = pdfBuffer.toString('base64');

const response = await ai.models.generateContent({
  model: 'gemini-2.5-flash',
  contents: [
    { text: 'Summarize this document' },
    {
      inlineData: {
        mimeType: 'application/pdf',
        data: base64Data,
      },
    },
  ],
});
```

### API Parameters - File Upload

```typescript
import { createPartFromUri, GoogleGenAI } from '@google/genai';

const ai = new GoogleGenAI({ apiKey: process.env.GEMINI_API_KEY });

// Upload file (persists for 48 hours)
const file = await ai.files.upload({
  file: 'large-document.pdf',
  config: { mimeType: 'application/pdf' },
});

// Wait for processing
let uploadedFile = await ai.files.get({ name: file.name });
while (uploadedFile.state === 'PROCESSING') {
  await new Promise((resolve) => setTimeout(resolve, 5000));
  uploadedFile = await ai.files.get({ name: file.name });
}

// Use in generation
const response = await ai.models.generateContent({
  model: 'gemini-2.5-flash',
  contents: [
    { text: 'Extract all tables from this document' },
    createPartFromUri(uploadedFile.uri, uploadedFile.mimeType),
  ],
});
```

### Implementation for gemini-client.ts

```typescript
interface FileAnalysisResult {
  text: string;
  fileUri?: string;
}

export async function analyzeFile(
  prompt: string,
  fileData: Buffer | string, // Buffer for binary, string for file path
  mimeType: string
): Promise<FileAnalysisResult> {
  let contents: any[];

  if (typeof fileData === 'string') {
    // File path - upload first
    const file = await genAI.files.upload({
      file: fileData,
      config: { mimeType },
    });

    // Wait for processing
    let uploaded = await genAI.files.get({ name: file.name });
    while (uploaded.state === 'PROCESSING') {
      await new Promise((r) => setTimeout(r, 2000));
      uploaded = await genAI.files.get({ name: file.name });
    }

    contents = [
      { text: prompt },
      { fileData: { fileUri: uploaded.uri, mimeType: uploaded.mimeType } },
    ];

    const response = await genAI.models.generateContent({
      model: flashModelName,
      contents,
    });

    return { text: response.text || '', fileUri: uploaded.uri };
  } else {
    // Buffer - use inline data
    const base64Data = fileData.toString('base64');
    contents = [
      { text: prompt },
      { inlineData: { mimeType, data: base64Data } },
    ];

    const response = await genAI.models.generateContent({
      model: flashModelName,
      contents,
    });

    return { text: response.text || '' };
  }
}
```

### Pricing & Limits
- Native text in PDFs is extracted free of charge
- Images count toward IMAGE modality in usage_metadata
- Token cost: ~258 tokens per page
- File API storage: 20GB per project, 2GB per file
- File retention: 48 hours

### Limitations
- Models may struggle with precise text/object location
- Handwritten text interpretation may have hallucinations
- Best with correctly oriented, non-blurry pages

---

## 6. Multi-Tool Usage

### Overview
Combine multiple tools in a single request for complex workflows.

```typescript
const response = await ai.models.generateContent({
  model: 'gemini-2.5-flash',
  contents: 'Search for the latest Python release date and calculate how many days ago it was',
  config: {
    tools: [
      { googleSearch: {} },
      { codeExecution: {} },
    ],
  },
});
```

### Supported Combinations
- Google Search + Code Execution
- Google Search + URL Context
- Code Execution + URL Context
- All three together

---

## Recommended MCP Tool Additions

Based on this research, here are the recommended new tools for gemini-mcp:

### 1. `gemini-search`
Real-time web search with grounded responses and source citations.

### 2. `gemini-execute-code`
Execute Python code for calculations, data analysis, and visualization.

### 3. `gemini-analyze-url`
Analyze web pages, PDFs, and documents from URLs.

### 4. `gemini-analyze-file`
Process uploaded files (PDFs, images) for analysis.

### 5. `gemini-function-call`
Define custom functions for structured data extraction.

---

## SDK Update Required

The current gemini-mcp uses `@google/genai` version `^0.10.0`. Update to the latest version (1.34.0+) for full feature support:

```bash
npm install @google/genai@latest
```

The newer SDK versions have better TypeScript types and support for all features documented here.

---

## Sources

- [Grounding with Google Search](https://ai.google.dev/gemini-api/docs/google-search)
- [Code Execution](https://ai.google.dev/gemini-api/docs/code-execution)
- [URL Context](https://ai.google.dev/gemini-api/docs/url-context)
- [Function Calling](https://ai.google.dev/gemini-api/docs/function-calling)
- [Document Understanding](https://ai.google.dev/gemini-api/docs/document-processing)
- [Image Understanding](https://ai.google.dev/gemini-api/docs/image-understanding)
- [Gemini Models](https://ai.google.dev/gemini-api/docs/models)
- [Gemini API Pricing](https://ai.google.dev/gemini-api/docs/pricing)
- [Rate Limits](https://ai.google.dev/gemini-api/docs/rate-limits)
- [@google/genai npm](https://www.npmjs.com/package/@google/genai)
- [js-genai GitHub](https://github.com/googleapis/js-genai)
