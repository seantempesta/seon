---
type: reference
status: active
tags: [reference, trading]
---
# ThetaData MCP (Model Context Protocol) Reference

This document provides setup and usage information for ThetaData's MCP server integration with Claude Code and other LLM CLI tools.

## Overview

ThetaData Terminal V3 includes an MCP (Model Context Protocol) server that enables natural language interaction with the ThetaData API. Instead of constructing complex REST API URLs, you can use natural language prompts to query market data.

**Key Benefits:**

- Natural language queries instead of manual API construction
- Automatic parameter parsing and validation
- Direct integration with LLM tools like Claude Code and Gemini CLI
- Access to full ThetaData API functionality (stocks, options, indices, Greeks, etc.)

**Status:** Currently in beta (as of August 2025)

## System Requirements

### Prerequisites

- **Theta Terminal V3** - Latest version (requirement enforced)
- **Active ThetaData subscription** - Standard tier includes:
  - Options: STANDARD tier
  - Stock: FREE tier
  - Index: FREE tier
- **Node.js/npm** - For installing CLI tools
- **Port 25503** - Must be accessible (check firewall/VPN settings)

### Important Notes

- **Theta Terminal must be running** for MCP to work
- The MCP server runs locally on your machine
- Network connectivity requirements apply (firewall/VPN considerations)

## Setup Instructions

### Claude Code Setup (Recommended)

1. **Install Claude Code** (if not already installed):

```bash
npm install -g @anthropic-ai/claude-code
```

1. **Add ThetaData MCP Server**:

```bash
claude mcp add --transport sse ThetaData http://localhost:25503/mcp/sse
```

1. **Verify Connection**:
   - Start Theta Terminal V3
   - Open Claude Code CLI
   - Run `/mcp` command to verify ThetaData server is connected

### Gemini CLI Setup (Alternative)

1. **Install Gemini CLI**:

```bash
npm install -g @google/gemini-cli
```

1. **Configure MCP Server**:
Edit `~/.gemini/settings.json` and add:

```json
{
  "mcpServers": {
    "ThetaData": {
      "url": "http://localhost:25503/mcp/sse",
      "timeout": 30000
    }
  }
}
```

1. **Verify Connection**:
   - Start Theta Terminal V3
   - Open Gemini CLI
   - Verify MCP connection is active

## Connection Details

### Endpoint Configuration

- **Transport Method:** Server-Sent Events (SSE)
- **URL:** `http://localhost:25503/mcp/sse`
- **Default Timeout:** 30000ms (30 seconds)
- **Protocol:** HTTP (local only)

### Connection Workflow

1. Start Theta Terminal V3
2. Terminal automatically starts MCP server on port 25503
3. Open your LLM CLI tool
4. Verify connection with `/mcp` command
5. Submit natural language queries

## Available Data Types

The MCP server provides access to all ThetaData V3 API endpoints, including:

### Stock Data

- Real-time snapshots
- Historical OHLC/EOD data
- Quote and trade history
- At-time queries

### Option Data

- **Greeks** (delta, gamma, theta, vega, rho)
  - Second-order Greeks
  - Historical Greeks (EOD, intraday)
  - Trade-level Greeks
- **Implied Volatility**
- **Open Interest**
- **Option chains** (all strikes/expirations)
- **Quote and trade data**

### Index Data

- Major indices (SPX, NDX, etc.)
- Index snapshots and history

### Calendar Data

- Earnings dates
- Ex-dividend dates
- Corporate actions

## Query Examples

### Basic Option Greeks Query

**Natural Language:**

```
Get the eod greek for last week for AAPL strike 200.00 CALL and expiration 2025-08-01
```

**Equivalent REST API:**

```
http://localhost:25503/v3/option/history/greeks/eod?symbol=AAPL&right=C&strike=200.0&expiration=2025-08-01&start_date=2025-07-28&end_date=2025-08-01
```

### Query with Formatting Instructions

**Natural Language:**

```
Get the eod greek for last week for AAPL strike 200.00 CALL and expiration 2025-08-01. Put this in a table showing the delta change.
```

The LLM will:

1. Execute the query
2. Format results as requested
3. Calculate delta changes between days
4. Display in table format

### Option Snapshot (All Greeks)

**Natural Language:**

```
Get all Greeks for all AAPL options
```

**Equivalent REST API:**

```
http://localhost:25503/v3/option/snapshot/greeks/all?symbol=AAPL&expiration=*
```

### Stock Historical Data

**Natural Language:**

```
Get daily OHLC data for TSLA for the past month
```

### Multiple Queries

**Natural Language:**

```
Compare the implied volatility for AAPL and MSFT 30-day ATM calls
```

## Query Optimization Tips

### Be Explicit with Parameters

**Good:**

```
Get EOD Greeks for AAPL strike 150.00 CALL expiration 2025-12-19 from 2025-11-01 to 2025-11-28
```

**Avoid:**

```
Get some AAPL option data from last month
```

### Recommended Specifications

1. **Symbols:** Use exact ticker symbols (AAPL, not Apple)
2. **Dates:** Use YYYY-MM-DD format (2025-11-28)
3. **Strikes:** Use decimals (150.00, not 150)
4. **Rights:** Use full words (CALL/PUT) or C/P
5. **Expirations:** Use full date format (2025-12-19)

### Handling Large Result Sets

**Problem:** Broad queries can return massive datasets

```
Get all options data for SPY  # Too broad!
```

**Solution:** Narrow your request

```
Get EOD Greeks for SPY strikes 550-560 CALL expiration 2025-12-20 for the past week
```

### Adding Context/Formatting

You can include formatting or analysis instructions:

- "Put this in a table"
- "Show the delta change over time"
- "Calculate the average implied volatility"
- "Sort by strike price"
- "Highlight the highest gamma values"

## Greek Calculations (Technical Details)

ThetaData calculates Greeks with the following methodology:

### Calculation Method

- **Frequency:** Greeks calculated for each tick of data
- **Underlying Price:** Uses exact underlying price at time of option tick
- **IV Calculation:** Fast bisection method for implied volatility
- **Interest Rate:** Uses SOFR (Secured Overnight Financing Rate) by default
  - SOFR reported 1 day after report date
  - Current day calculations use last available SOFR rate

### Available Greeks

**First-Order Greeks:**

- Delta: Rate of change of option price with respect to underlying price
- Gamma: Rate of change of delta with respect to underlying price
- Theta: Rate of change of option price with respect to time
- Vega: Rate of change of option price with respect to volatility
- Rho: Rate of change of option price with respect to interest rate

**Second-Order Greeks:**

- Available through trade Greeks endpoints
- More advanced sensitivity metrics

## Troubleshooting

### Connection Issues

**Problem:** MCP server not detected

**Solutions:**

1. Verify Theta Terminal V3 is running
2. Check Terminal is latest version
3. Confirm port 25503 is accessible
4. Check firewall/VPN settings
5. Restart LLM CLI after Terminal startup
6. Verify config file accuracy:
   - Claude: Check MCP registration with `/mcp`
   - Gemini: Check `~/.gemini/settings.json`

### Query Failures

**Problem:** Query returns no data or errors

**Solutions:**

1. Verify your subscription tier includes requested data type
2. Check date ranges are valid (not future dates)
3. Ensure symbol is valid and available
4. Narrow broad queries (large result sets may timeout)
5. Check Terminal is connected to ThetaData servers
6. Verify strike/expiration combinations exist

### Performance Issues

**Problem:** Slow responses or timeouts

**Solutions:**

1. Reduce date range for historical queries
2. Limit number of strikes/expirations requested
3. Query specific contracts instead of broad searches
4. Increase timeout setting (Gemini: edit config)
5. Check network connectivity to ThetaData servers

## Data Access by Subscription Tier

Your ThetaData subscription tier determines available data:

### Standard Tier (Most Common)

- **Options:** STANDARD access
  - All strikes and expirations
  - Greeks, IV, OI, quotes, trades
  - Historical and real-time data
- **Stocks:** FREE access
  - Basic quotes and trades
  - Limited historical depth
- **Indices:** FREE access
  - Major index data

### Verify Your Access

Check your subscription tier in Theta Terminal settings to confirm available data types.

## Best Practices

### 1. Start Theta Terminal First

Always launch Theta Terminal V3 before using the LLM CLI to ensure MCP server is available.

### 2. Use Precise Queries

The more specific your query, the better the results:

- Include exact dates
- Specify strike prices with decimals
- Use full ticker symbols
- Specify option right (CALL/PUT)

### 3. Leverage Formatting Instructions

Take advantage of LLM capabilities:

- Request specific output formats
- Ask for calculations or transformations
- Request visualizations or comparisons

### 4. Iterate on Queries

If initial query doesn't return desired results:

- Refine parameters
- Narrow date ranges
- Add more specific criteria
- Request different data fields

### 5. Monitor Resource Usage

For large queries:

- Start with smaller date ranges
- Test with single symbols before bulk queries
- Consider API rate limits
- Monitor Terminal performance

## Additional Resources

- **ThetaData MCP Documentation:** <https://docs.thetadata.us/Mcp/Getting-Started.html>
- **ThetaData API Reference:** <https://docs.thetadata.us/>
- **Option Greeks Guide:** <https://http-docs.thetadata.us/Articles/Data-And-Requests/Option-Greeks.html>
- **Model Context Protocol Specification:** <https://modelcontextprotocol.io/>
- **ThetaData Blog (MCP Announcement):** <https://www.thetadata.net/post/mcp-now-in-terminal-v3-beta>

## Feedback and Support

The MCP feature is currently in beta. ThetaData is actively collecting feedback on:

- Use cases and workflows
- Feature requests
- Performance issues
- Integration improvements

Contact ThetaData support through their website or Terminal for assistance.

---

**Last Updated:** November 2025
**ThetaData Version:** Terminal V3 (Beta)
**MCP Protocol Version:** Server-Sent Events (SSE)
