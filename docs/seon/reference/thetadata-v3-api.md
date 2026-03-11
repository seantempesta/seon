---
type: reference
status: active
tags: [reference, trading]
---
# ThetaData v3 REST API Reference

## API Overview

### Base Configuration

- **Base URL**: `http://localhost:25503/v3/`
- **Protocol**: HTTP REST API (local Theta Terminal v3)
- **Default Port**: 25503
- **WebSocket Port**: 25520 (for FPSS streaming)
- **Authentication**: Credentials stored in `thetadata/creds.txt`

### Requirements

- Theta Terminal v3 must be running locally
- Standard tier includes: Options STANDARD, Stock FREE, Index FREE
- Some endpoints require Pro subscription tier

### Supported Formats

All endpoints support multiple response formats via the `format` parameter:

- `csv` (default)
- `json`
- `ndjson`
- `html`

### Time Intervals

Standard interval options across endpoints:

- Tick-level: `tick`
- Sub-second: `10ms`, `100ms`, `500ms`
- Seconds: `1s`, `5s`, `10s`, `15s`, `30s`
- Minutes: `1m`, `5m`, `10m`, `15m`, `30m`
- Hours: `1h`

### Default Trading Hours

- **start_time**: `09:30:00` (market open)
- **end_time**: `16:00:00` (market close)
- Format: `HH:mm:ss`

---

## Options Endpoints

### 1. Option History Greeks (All)

**Endpoint**: `GET /v3/option/history/greeks/all`

**Tier**: Pro subscription required

**Description**: Returns comprehensive Greeks calculations for option contracts with intraday granularity.

**Required Parameters**:

- `symbol` (string): Stock/index symbol or underlying
- `date` (string): Target date for data retrieval
- `expiration` (string): Contract expiration (YYYY-MM-DD or YYYYMMDD)
- `interval` (string): Time interval (highly recommended)

**Optional Parameters**:

- `strike` (string): Dollar amount (e.g., 100.00) or `*` for all (default: `*`)
- `right` (string): `call`, `put`, or `both` (default: `both`)
- `start_time` (string): HH:mm:ss format (default: `09:30:00`)
- `end_time` (string): HH:mm:ss format (default: `16:00:00`)
- `annual_dividend` (number): Annualized dividend for calculations
- `rate_type` (string): `sofr` or treasury options (m1-y30) (default: `sofr`)
- `rate_value` (number): Interest rate as percentage
- `format` (string): csv, json, ndjson, html (default: `csv`)

**Response Fields**:

*Identifiers*:

- `symbol`, `expiration`, `strike`, `right`, `timestamp`

*Market Data*:

- `bid`, `ask`, `underlying_price`, `underlying_timestamp`

*First-Order Greeks*:

- `delta`, `theta`, `vega`, `rho`, `epsilon`, `lambda`, `gamma`

*Second-Order Greeks*:

- `vanna`, `charm`, `vomma`, `veta`

*Third-Order Greeks*:

- `vera`, `speed`, `zomma`, `color`, `ultima`

*Volatility Metrics*:

- `implied_vol`, `iv_error`, `d1`, `d2`, `dual_delta`, `dual_gamma`

**Example**:

```
http://localhost:25503/v3/option/history/greeks/all?symbol=AAPL&expiration=20241108&date=20241104&interval=10m

```

**Notes**:

- Greeks calculated using option and underlying midpoint price
- When interval is specified, quote data follows same rules as quote endpoint
- Underlying price represents the last underlying price at the timestamp field

---

### 2. Option History Greeks (EOD)

**Endpoint**: `GET /v3/option/history/greeks/eod`

**Description**: Returns end-of-day Greeks calculations using closing prices.

**Required Parameters**:

- `symbol` (string): Stock or index symbol
- `expiration` (string): Contract expiration (YYYY-MM-DD/YYYYMMDD) or `*` for all
- `start_date` (string): Inclusive start date
- `end_date` (string): Inclusive end date

**Optional Parameters**:

- `strike` (string): Strike price in dollars or `*` for all (default: `*`)
- `right` (string): `call`, `put`, or `both` (default: `both`)
- `annual_dividend` (number): Annualized expected dividend
- `rate_type` (string): Interest rate type (default: `sofr`)
- `rate_value` (number): Interest rate as percentage
- `format` (string): csv, json, ndjson, html (default: `csv`)

**Response Fields**: Same as Option History Greeks (All) plus OHLC data

**Example**:

```
# Single contract
http://localhost:25503/v3/option/history/greeks/eod?symbol=AAPL&expiration=20241108&strike=220.000&right=call&start_date=20241104&end_date=20241104

# All contracts
http://localhost:25503/v3/option/history/greeks/eod?symbol=AAPL&expiration=*&start_date=20241104&end_date=20241104

```

**Notes**:

- Uses daily EOD reports generated at 17:15 ET
- Quote fields (bid/ask) may lack data prior to December 1, 2023
- Set `expiration=*` to retrieve data for every option with the same symbol

---

### 3. Option History Quote

**Endpoint**: `GET /v3/option/history/quote`

**Description**: Returns every NBBO quote reported by OPRA.

**Required Parameters**:

- `symbol` (string): Stock/index symbol or underlying
- `expiration` (string): Contract expiration (YYYY-MM-DD/YYYYMMDD) or `*`
- `date` (string): Date to fetch data
- `interval` (string): Time interval size

**Optional Parameters**:

- `strike` (string): Strike price or `*` (default: `*`)
- `right` (string): `call`, `put`, or `both` (default: `both`)
- `start_time` (string): Default `09:30:00`
- `end_time` (string): Default `16:00:00`
- `format` (string): csv, json, ndjson, html (default: `csv`)

**Response Fields**:

- `symbol`, `expiration`, `strike`, `right`
- `timestamp` (YYYY-MM-DDTHH:mm:ss.SSS)
- `bid_size`, `bid_exchange`, `bid`, `bid_condition`
- `ask_size`, `ask_exchange`, `ask`, `ask_condition`

**Example**:

```
http://localhost:25503/v3/option/history/quote?symbol=AAPL&expiration=20241108&strike=220.000&right=call&date=20241104&interval=1m

```

---

### 4. Option History Trade

**Endpoint**: `GET /v3/option/history/trade`

**Description**: Returns every trade reported by OPRA.

**Required Parameters**:

- `symbol` (string): Stock/index symbol or underlying
- `expiration` (string): Contract expiration or `*`
- `date` (string): Date to fetch data

**Optional Parameters**:

- `strike` (string): Strike price or `*` (default: `*`)
- `right` (string): `call`, `put`, or `both` (default: `both`)
- `start_time` (string): Default `09:30:00`
- `end_time` (string): Default `16:00:00`
- `format` (string): csv, json, ndjson, html (default: `csv`)

**Response Fields**:

- `symbol`, `expiration`, `strike`, `right`
- `timestamp` (date-time), `sequence` (integer)
- `condition`, `ext_condition1-4` (trade conditions)
- `size` (contracts traded), `exchange`, `price`

**Example**:

```
# Single contract
http://localhost:25503/v3/option/history/trade?symbol=AAPL&expiration=20241108&strike=220.000&right=call&date=20241104

# All contracts
http://localhost:25503/v3/option/history/trade?symbol=AAPL&expiration=*&date=20241104

```

**Notes**:

- Extended trade conditions can be ignored for options

---

### 5. Option History OHLC

**Endpoint**: `GET /v3/option/history/ohlc`

**Description**: Returns aggregated OHLC bars for option contracts.

**Required Parameters**:

- `symbol` (string): Stock/index symbol or underlying
- `expiration` (string): Contract expiration
- `date` (string): Date to fetch data
- `interval` (string): Time interval (default: `1s`)

**Optional Parameters**:

- `strike` (string): Strike price or `*` (default: `*`)
- `right` (string): `call`, `put`, or `both` (default: `both`)
- `start_time` (string): Default `09:30:00`
- `end_time` (string): Default `16:00:00`
- `format` (string): csv, json, ndjson, html (default: `csv`)

**Response Fields**:

- `symbol`, `expiration`, `strike`, `right`, `timestamp`
- `open`, `high`, `low`, `close`
- `volume`, `count`
- `vwap` (volume weighted average price)

**Example**:

```
http://localhost:25503/v3/option/history/ohlc?symbol=AAPL&expiration=20231103&strike=170.000&right=call&date=20231103&interval=1m

```

**Notes**:

- Timestamp represents the opening time of the bar
- Trades qualify for inclusion based on this timing rule

---

### 6. Option List Expirations

**Endpoint**: `GET /v3/option/list/expirations`

**Description**: Lists all available expiration dates for an option symbol.

**Required Parameters**:

- `symbol` (string): Underlying symbol, `*` for all, or comma-separated list

**Optional Parameters**:

- `format` (string): csv, json, ndjson, html (default: `csv`)

**Response Fields**:

- `symbol` (string): Option contract or underlying symbol
- `expiration` (string): Expiration date (YYYY-MM-DD)

**Example**:

```
http://localhost:25503/v3/option/list/expirations?symbol=AAPL

```

**Notes**:

- Updated overnight
- Available on Free, Value, Standard, and Pro plans

---

### 7. Option List Strikes

**Endpoint**: `GET /v3/option/list/strikes`

**Description**: Lists all available strike prices for a symbol/expiration.

**Required Parameters**:

- `symbol` (array): Stock/index symbol, `*`, or comma-separated list
- `expiration` (string): Contract expiration (YYYY-MM-DD or YYYYMMDD)

**Optional Parameters**:

- `format` (string): csv, json, ndjson, html (default: `csv`)

**Response Fields**:

- `symbol` (string): Contract symbol
- `strike` (number): Strike price in dollars (e.g., 180.00)

**Example**:

```
http://localhost:25503/v3/option/list/strikes?symbol=AAPL&expiration=20220930

```

**Notes**:

- Updated overnight

---

## Stock Endpoints

### 1. Stock History Quote

**Endpoint**: `GET /v3/stock/history/quote`

**Description**: Returns every NBBO quote reported by UTP and CTA.

**Required Parameters**:

- `symbol` (string): Stock or index symbol
- `date` (string): Date to fetch data
- `interval` (string): Time interval (default: `1s`)

**Optional Parameters**:

- `start_time` (string): Default `09:30:00`
- `end_time` (string): Default `16:00:00`
- `venue` (string): `nqb` or `utp_cta` (default: `nqb`)
- `format` (string): csv, json, ndjson, html (default: `csv`)

**Response Fields**:

- `timestamp` (YYYY-MM-DDTHH:mm:ss.SSS)
- `bid_size`, `bid_exchange`, `bid`, `bid_condition`
- `ask_size`, `ask_exchange`, `ask`, `ask_condition`

**Example**:

```
http://localhost:25503/v3/stock/history/quote?symbol=AAPL&date=20240102&interval=1m

```

**Notes**:

- When interval specified, quote represents last quote prior to interval's timestamp

---

### 2. Stock History OHLC

**Endpoint**: `GET /v3/stock/history/ohlc`

**Description**: Returns aggregated OHLC bars using SIP rules.

**Required Parameters**:

- `symbol` (string): Stock or index symbol
- `date` (string): Date to fetch data
- `interval` (string): Bar size

**Optional Parameters**:

- `start_time` (string): Default `09:30:00`
- `end_time` (string): Default `16:00:00`
- `venue` (string): `nqb` or `utp_cta` (default: `nqb`)
- `format` (string): csv, json, ndjson, html (default: `csv`)

**Response Fields**:

```json
{
  "timestamp": "string (date-time)",
  "open": "number",
  "high": "number",
  "low": "number",
  "close": "number",
  "volume": "integer",
  "count": "integer",
  "vwap": "number"
}

```

**Example**:

```
http://localhost:25503/v3/stock/history/ohlc?symbol=AAPL&date=20240102&interval=1m

```

**Notes**:

- Timestamp represents opening time of each bar

---

## Data Format Notes

### Greeks Scaling and Calculations

**Important**: Greeks values returned by ThetaData may require scaling:

1. **Vega Scaling**: Vega is typically scaled by 100
   - Raw value: 0.1234
   - Actual vega: 0.1234 / 100 = 0.001234

2. **Rho Scaling**: Rho is typically scaled by 100
   - Raw value: 0.5678
   - Actual rho: 0.5678 / 100 = 0.005678

3. **Delta**: No scaling needed (range: -1 to 1 for puts/calls)

4. **Gamma**: No scaling needed

5. **Theta**: No scaling needed (daily decay)

**Calculation Methodology**:

- Greeks are calculated using option and underlying midpoint prices
- Underlying price is the last available price at the timestamp
- Uses Black-Scholes model with specified interest rate and dividend inputs

### Interest Rate Options

The `rate_type` parameter supports:

- `sofr` (default): Secured Overnight Financing Rate
- Treasury rates: `m1`, `m2`, `m3`, `m6`, `y1`, `y2`, `y3`, `y5`, `y7`, `y10`, `y20`, `y30`

### Date Formats

Dates can be provided in two formats:

- ISO format: `YYYY-MM-DD` (e.g., `2024-11-08`)
- Compact format: `YYYYMMDD` (e.g., `20241108`)

### Timestamp Format

All timestamps are returned in ISO 8601 format with millisecond precision:

- Format: `YYYY-MM-DDTHH:mm:ss.SSS`
- Example: `2024-11-04T10:30:00.000`

### Wildcard Support

Many endpoints support wildcards for bulk data retrieval:

- `expiration=*`: All expirations for a symbol
- `strike=*`: All strikes for an expiration
- `symbol=*`: All symbols (list endpoints only)

---

## Error Handling

### Common Error Scenarios

1. **Terminal Not Running**
   - Error: Connection refused to localhost:25503
   - Solution: Start Theta Terminal v3

2. **Invalid Date Range**
   - Error: Date outside subscription coverage
   - Solution: Check subscription tier data access

3. **Invalid Parameters**
   - Error: 400 Bad Request
   - Solution: Verify parameter formats and required fields

4. **Subscription Limits**
   - Error: 403 Forbidden or tier restriction message
   - Solution: Upgrade subscription or use supported endpoints

5. **Rate Limiting**
   - ThetaData implements rate limiting (specific limits not documented)
   - Solution: Implement exponential backoff for bulk requests

### Response Status Codes

- `200 OK`: Successful request
- `400 Bad Request`: Invalid parameters
- `403 Forbidden`: Subscription tier restriction
- `404 Not Found`: Symbol or contract not found
- `500 Internal Server Error`: Server-side error

---

## Rate Limits

**Note**: Specific rate limit values are not publicly documented in the API reference.

**Best Practices**:

- Implement exponential backoff for bulk requests
- Use bulk endpoints (`expiration=*`, `strike=*`) when possible
- Cache overnight-updated data (list endpoints)
- Monitor response times and adjust request frequency

---

## Configuration Reference

From `thetadata/config.toml`:

```toml
# REST API Configuration
host = "0.0.0.0"
port = 25503

# MDDS Server (Theta Data backend)
[mdds_server]
host = "mdds-01.thetadata.us"
port = 443
tls = true

# Streaming (FPSS) Configuration
[fpss]
enable = true
reconnect_wait = 1000
fpss_queue_depth = 1000000
ws_port = 25520
fpss_region = "fpss_nj_hosts"

```

---

## Subscription Tiers

### Standard Tier (Current)

- **Options**: STANDARD access
- **Stock**: FREE access
- **Index**: FREE access

### Pro Tier (Upgrade Required)

- Required for: Option History Greeks (All) intraday endpoint
- Includes all Standard tier access
- Additional real-time features

---

## Common Use Cases

### 1. Fetch Historical Greeks for Backtesting

```bash
# End-of-day Greeks for all AAPL options on a date range
curl "http://localhost:25503/v3/option/history/greeks/eod?symbol=AAPL&expiration=*&start_date=20240101&end_date=20240131&format=json"

```

### 2. Get Current Option Chain

```bash
# List all expirations
curl "http://localhost:25503/v3/option/list/expirations?symbol=AAPL&format=json"

# List strikes for specific expiration
curl "http://localhost:25503/v3/option/list/strikes?symbol=AAPL&expiration=20241220&format=json"

```

### 3. Fetch Underlying Stock Data

```bash
# 1-minute OHLC bars for a day
curl "http://localhost:25503/v3/stock/history/ohlc?symbol=AAPL&date=20241104&interval=1m&format=json"

```

### 4. Get Intraday Option Quotes

```bash
# 5-minute quote snapshots for a specific option
curl "http://localhost:25503/v3/option/history/quote?symbol=AAPL&expiration=20241220&strike=180.00&right=call&date=20241104&interval=5m&format=json"

```

---

## Additional Resources

- **ThetaData Documentation**: <https://docs.thetadata.us/>
- **Support**: Check ThetaData support channels
- **Python SDK**: Available but this document focuses on REST API
- **WebSocket Streaming**: Port 25520 (FPSS) for real-time data

---

## Notes for ML Options Trading Project

### Data Pipeline Integration

1. **Primary Greeks Source**: Use EOD Greeks endpoint for historical backtesting
2. **Chain Discovery**: Use list endpoints (expirations, strikes) to discover available contracts
3. **Underlying Prices**: Fetch stock OHLC for base price data
4. **Scaling**: Remember to apply vega/100 and rho/100 scaling when storing in XTDB

### Recommended Endpoints for MVP

| Endpoint | Purpose | Priority |
|----------|---------|----------|
| `/v3/option/history/greeks/eod` | Historical Greeks data | High |
| `/v3/option/list/expirations` | Contract discovery | High |
| `/v3/option/list/strikes` | Contract discovery | High |
| `/v3/stock/history/ohlc` | Underlying prices | Medium |
| `/v3/option/history/quote` | Quote data validation | Low |

### Data Quality Considerations

- Quote data (bid/ask) limited before December 1, 2023
- EOD reports generated at 17:15 ET
- List endpoints updated overnight
- Greeks calculated using midpoint prices (more stable than last trade)
