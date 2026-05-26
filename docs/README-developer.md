# System Composition — Developer Guide

This document covers the SPARQL queries, data model, Pixel API, and internal logic for the Java reactors powering this application. The backend supersedes two legacy SEMOSS playsheet insights — insight #21 (`GraphTimePlaySheet`) for composition-over-time and insight #140 (`InterfaceGraphPlaySheet`) for the network-of-systems view. All data access is pure SPARQL over three configured RDF database engines. There are no external HTTP calls, no file I/O beyond config loading.

---

## Reactor Summaries

| Reactor | Pixel Command | Purpose |
|---|---|---|
| `ListActiveSystemsReactor` | `ListActiveSystems` | Returns all `ActiveSystem` instances for the system-selector dropdown. Queries FutureDB (`decommissionEngineId`). |
| `GetCompositionTimelineReactor` | `GetCompositionTimeline` | Given a focal system URI, builds the full node/edge graph (Systems, ICDs, DataObjects) and applies four SPARQL overlay passes to stamp each node with phase, LOE, and decommission data. Replicates legacy insight #21. |
| `ListDataObjectsReactor` | `ListDataObjects` | Returns all distinct `DataObject` instances from the base engine for the data-object selector in the Network of Systems view. |
| `GetGraphForDataObjectReactor` | `GetGraphForDataObject` | Given a focal data object URI, finds all provider systems and inter-system interfaces carrying that data object and assembles the full node/edge graph. Replicates legacy insight #140. |
| `RunDebugSparqlReactor` | `RunDebugSparql` | Debug-only. Executes an arbitrary SPARQL SELECT against a specified engine with a configurable row cap. Powers the SPARQL Inspector page. |
| `CompareGraphOutputsReactor` | `CompareGraphOutputs` | Debug-only. Diffs node/edge sets between the legacy insight #140 playsheet and `GetGraphForDataObjectReactor` for the same data object. |
| `CompareCompositionTimelineReactor` | `CompareCompositionTimeline` | Debug-only. Validates `GetCompositionTimelineReactor` against legacy insight #21 across structural nodes/edges and timeHash overlay values, with normalization for literal type differences. |
| `InspectLOEOverlayReactor` | `InspectLOEOverlay` | Debug-only. Surfaces `(futureICD, phase)` groups where multiple TransitionGLItem rows compete under the Q1 GROUP BY, diagnosing last-write-wins ambiguity. |

---

## Project Structure

```
java/
├── project.properties              Engine UUIDs (3 RDF databases)
└── src/
    ├── reactors/
    │   ├── AbstractProjectReactor.java         Base class for all reactors
    │   ├── compositionTimeline/
    │   │   ├── GetCompositionTimelineReactor.java
    │   │   └── ListActiveSystemsReactor.java
    │   ├── networkOfSystems/
    │   │   ├── GetGraphForDataObjectReactor.java
    │   │   └── ListDataObjectsReactor.java
    │   └── debug/
    │       ├── CompareCompositionTimelineReactor.java
    │       ├── CompareGraphOutputsReactor.java
    │       ├── InspectLOEOverlayReactor.java
    │       └── RunDebugSparqlReactor.java
    └── util/
        ├── Constants.java          Placeholder (currently empty)
        ├── HelperMethods.java      Placeholder (currently empty)
        ├── ProjectProperties.java  Config singleton
        └── QueryExecutor.java      SPARQL SELECT wrapper
```

---

## Configuration — `project.properties`

Three RDF engine UUIDs must be configured:

| Property Key | Engine | Role |
|---|---|---|
| `baseEngineId` | `TAP_Core_Data` | Base graph CONSTRUCT + Q3/Q4 BoS overlays |
| `decommissionEngineId` | `FutureDB` | Project core engine + Q2 decommissioned ICD overlay |
| `transitionEngineId` | `FutureCostDB` | Q1 TransitionGLItem / LOE phase overlay |

**Current values:**
```properties
baseEngineId=133db94b-4371-4763-bff9-edf7e5ed021b
decommissionEngineId=df69df03-45f6-483a-af34-9c4d20bb6b7c
transitionEngineId=6897e1e5-3604-464d-bf26-c675eac23d26
```

> **Note:** `ProjectProperties` is a **process-scoped singleton** — it is loaded once and never invalidated. If engine IDs change in `project.properties`, the SEMOSS process must be restarted.

---

## Utility Classes

### `ProjectProperties`

Singleton that loads `project.properties` from disk on first access.

**Initialization:** Called in `AbstractProjectReactor.preExecute()` via `ProjectProperties.getInstance(projectId)`. Path is resolved using `AssetUtility.getProjectAssetsFolder(projectId) + "/java/project.properties"`.

**Exposes:**
- `getBaseEngineId()`
- `getDecommissionEngineId()`
- `getTransitionEngineId()`

**Error behavior:** If the file is missing, `INSTANCE` stays `null` and calling `getInstance()` (no-arg) throws `RuntimeException`.

---

### `QueryExecutor`

Thin wrapper around SEMOSS's `WrapperManager.getSWrapper()` for executing SPARQL SELECT queries.

**Construction:**
```java
new QueryExecutor(engineId)
```
Resolves the engine UUID via `MasterDatabaseUtility.testDatabaseIdIfAlias()` then fetches it via `Utility.getDatabase()`. Throws `IllegalArgumentException` if the engine cannot be resolved.

**Primary method:**
```java
List<Map<String, String>> executeSelect(String query)
```
Each map in the list represents one result row, keyed by SPARQL variable name (e.g., `"System"`, `"phase"`, `"LOE"`).

**Value resolution:** The private `chooseValue()` method returns the raw URI verbatim if the value looks like a URI (`http://`, `https://`, `urn:`, `file:`). Otherwise returns the display (human-readable local name).

**CONSTRUCT queries:** `QueryExecutor` supports SELECT only. CONSTRUCT queries are executed directly via `WrapperManager.getCWrapper()` inside `GetCompositionTimelineReactor`, using `executor.getEngine()`.

**Also exposes:** `getEngine()` and `getEngineId()`.

---

### `Constants` and `HelperMethods` (Placeholders)

Both classes are currently **empty scaffolding**. All constants and helpers are currently defined as `private static final` fields or private methods within each reactor class. If any become shared across reactors, they should be moved here.

Prime candidates for `HelperMethods`:
- `localName(String uri)` — extracts substring after last `/` or `#`
- `unwrapLiteral(String value)` — strips `^^<datatype>` suffixes, language tags, and outer quotes
- `buildValues(List<String>)` — produces `<uri1> <uri2> ...` for SPARQL VALUES clauses

---

## Reactor: `ListActiveSystemsReactor`

**Pixel call:** `ListActiveSystems()`  
**Parameters:** None  
**Engine:** `decommissionEngineId` (FutureDB)

Returns all `ActiveSystem` instances for the system-selector dropdown.

**SPARQL:**
```sparql
SELECT DISTINCT ?System WHERE {
  ?System rdf:type <http://semoss.org/ontologies/Concept/ActiveSystem> .
}
```

**Return shape** (`CUSTOM_DATA_STRUCTURE` → `List<Map>`):
```json
[
  { "uri": "http://health.mil/ontologies/Concept/ActiveSystem/MHS_GENESIS",
    "label": "MHS GENESIS" }
]
```
Sorted alphabetically by label. Label = URI local name with underscores replaced by spaces.

---

## Reactor: `GetCompositionTimelineReactor`

**Pixel call:**
```
GetCompositionTimeline(systemUri=["http://health.mil/ontologies/Concept/ActiveSystem/MHS_GENESIS"])
```

**Parameters:**

| Key | Required | Description |
|---|---|---|
| `systemUri` | Yes | Full URI of the focal `ActiveSystem` |
| `database` | No | Override base engine (default: `baseEngineId`) |
| `decommissionDatabase` | No | Override decommission engine |
| `transitionDatabase` | No | Override transition engine |

**Purpose:** Full replication of legacy insight #21. Builds a temporal graph of Systems, ICDs, and DataObjects, then applies four time overlays to mark nodes with phase/LOE/decommission data.

### Execution Flow (5 Stages)

**Stage 1: Base Graph (CONSTRUCT)**

Runs the same CONSTRUCT query against both `baseEngineId` and `decommissionEngineId`. The CONSTRUCT captures the focal system's full ICD neighborhood in two UNION branches:
- Branch 1: focal system as provider → `System1 --Provide--> ICD --Consume--> System2 --Payload--> DataObject`
- Branch 2: focal system as consumer → `System3 --Provide--> ICD2 --Consume--> System1 --Payload--> DataObject`

> CONSTRUCT is used (not SELECT) because the engine's OWL schema resolves `rdfs:subPropertyOf` for variable predicates inside CONSTRUCT, which `IRawSelectWrapper` does not support.

Triples from the CONSTRUCT are classified by predicate URI prefix:
- `Relation/Provide*` → system-to-ICD edge (`"Provide"`)
- `Relation/Consume*` → ICD-to-system edge (`"Consume"`)
- `Relation/Payload*` → ICD-to-DataObject edge (`"Payload"`)
- `Relation/Contains/*` → ICD property written into `propHash`

**Stage 1b: Payload Fallback SELECT**

ICDs with Payload connections but no Contains properties are missed by the CONSTRUCT. A fallback SELECT finds them:
```sparql
SELECT DISTINCT ?ICD ?Data WHERE {
  ?carries rdfs:subPropertyOf Relation/Payload .
  ?ICD ?carries ?Data .
  ?Data rdf:type Concept/DataObject .
  VALUES ?ICD { <icd1> <icd2> ... }
}
```
Runs against both base and decommission engines. Only adds missing edges.

**Stage 2: Q1 — LOE Overlay (FutureCostDB)**

```sparql
SELECT DISTINCT ?futureICD ?phase
  (CONCAT('[\"',GROUP_CONCAT(DISTINCT ?oldICD;SEPARATOR='","'),'\"]') AS ?dependICDS)
  ?GLitem (ROUND(?loe) AS ?LOE) ?gltag
WHERE {
  ?subclass rdfs:subClassOf Concept/TransitionGLItem .
  ?GLitem rdf:type ?subclass .
  ?tagged rdfs:subPropertyOf Relation/TaggedBy .
  ?gltag rdf:type Concept/GLTag .
  ?GLitem ?tagged ?gltag .
  ?influences rdfs:subPropertyOf Relation/Influences .
  ?sys ?influences ?GLitem .
  ?GLitem Contains/LOEcalc ?loe .
  ?phase rdf:type Concept/SDLCPhase .
  ?belongs rdfs:subPropertyOf Relation/BelongsTo .
  ?GLitem ?belongs ?phase .
  ?futureICD rdf:type Concept/SystemInterface .
  ?output rdfs:subPropertyOf Relation/Output .
  ?GLitem ?output ?futureICD .
  OPTIONAL { ... ?oldICD ?input ?GLitem }
} GROUP BY ?phase ?futureICD ?GLitem ?loe ?gltag
```

For each matching ICD, writes a `timeHash` entry keyed by phase local name: `{phase, LOE, dependICDS, GLitem, gltag}`. **Last-write-wins** (matches legacy `Hashtable.putAll()` behavior).

**Stage 3: Q2 — Decommissioned ICD Overlay (FutureDB)**

Marks ICDs of type `ProposedDecommissionedSystemInterface` with `timeHash["Decommissioned"]`.

**Stage 4: Q3 — BoS System Overlay (TAP_Core_Data)**

Marks ActiveSystem nodes with `Contains/Probability_of_Included_BoS_Enterprise_EHRS = 'High'` with `timeHash["Decommissioned"]`.

**Stage 5: Q4 — BoS ICD Overlay (TAP_Core_Data)**

Marks ICDs connected to any high-probability BoS system with `timeHash["Decommissioned"]`.

### Return Shape

```json
{
  "nodes": {
    "<systemUri>": {
      "uri": "...",
      "propHash": {
        "VERTEX_TYPE_PROPERTY": "System" | "SystemInterface" | "DataObject",
        "VERTEX_LABEL_PROPERTY": "...",
        "VERTEX_COLOR_PROPERTY": "31,119,180",
        "PhysicalName": "...",
        "URI": "...",
        "timeHash": {
          "Requirements": { "phase": "Requirements", "LOE": 12.0, "GLitem": "...", "gltag": "...", "dependICDS": "[\"OldICD1\"]" },
          "Decommissioned": { "phase": "Decommissioned", "LOE": 0.0 }
        }
      }
    }
  },
  "edges": [
    { "uri": "...", "source": "<sourceUri>", "target": "<targetUri>",
      "propHash": { "EDGE_TYPE": "Provide", "EDGE_NAME": "...", "URI": "..." } }
  ],
  "layout": "prerna.ui.components.specific.tap.GraphTimePlaySheet",
  "title": "How does the composition of this system change over time?",
  "dataMakerName": "GraphTimePlaySheet"
}
```

Node color values: System = `"31,119,180"` (blue), ICD = `"44,160,44"` (green), DataObject = `"255,127,14"` (orange).

### Internal Helpers

| Helper | Purpose |
|---|---|
| `resolveEngineId(paramKey, fallback)` | Prefer Pixel param, then `project.properties`, throw if neither |
| `safeExecute(executor, query)` | Catches exceptions, returns empty list (overlays are non-fatal) |
| `buildValues(List<String>)` | Produces `<uri1> <uri2> ...` for SPARQL VALUES clauses |
| `localName(String)` | Substring after last `/` or `#` |
| `unwrapLiteral(String)` | Strips `^^<datatype>`, language tags, outer quotes |
| `localNameList(String)` | Converts GROUP_CONCAT JSON array of URIs to JSON array of local names |
| `mergeTimeHash(nodeEntry, phaseKey, phaseData)` | Writes into `propHash.timeHash`, creating the map if absent |
| `addEdgeIfAbsent(edges, source, target, edgeType)` | Deduplicates by generated edge URI |

---

## Reactor: `ListDataObjectsReactor`

**Pixel call:** `ListDataObjects(database=["133db94b-..."])`

**Parameters:**

| Key | Required | Description |
|---|---|---|
| `database` | Yes | Engine UUID (typically `baseEngineId`) |

**SPARQL:**
```sparql
SELECT DISTINCT ?DataObject WHERE {
  ?DataObject rdf:type <http://semoss.org/ontologies/Concept/DataObject> .
} ORDER BY ?DataObject
```

**Return shape** (`CUSTOM_DATA_STRUCTURE` → `List<Map>`):
```json
[
  { "uri": "http://health.mil/ontologies/Concept/DataObject/Admissions",
    "label": "Admissions" }
]
```

---

## Reactor: `GetGraphForDataObjectReactor`

**Pixel call:**
```
GetGraphForDataObject(
  database=["133db94b-..."],
  dataObject=["http://health.mil/ontologies/Concept/DataObject/Admissions"]
)
```

**Parameters:**

| Key | Required | Description |
|---|---|---|
| `database` | Yes | RDF engine UUID to query |
| `dataObject` | Yes | Full URI of the focal `DataObject` |

**Purpose:** Replicates legacy insight #140. Builds a bipartite-like graph of `ActiveSystem` nodes connected to the focal DataObject via ICDs.

### Execution Flow (7 Stages)

1. **Provider systems** — SELECT for all ActiveSystem nodes that Provide the DataObject with `CRM = 'C'` or `'M'`
2. **System↔System edges** — SELECT for all System→System pairs connected via any ICD carrying the DataObject; edge `propHash` populated from `Contains` sub-properties
3. **ICD-mediated edge assembly** — adds deduplicated System↔System edges
4. **Provide edge assembly** — adds DataObject→System edges for each provider
5. **Provide edge properties** — loads `Contains` sub-properties for each Provide edge
6. **DataObject node** — includes provider count, `Outputs` count, `Contains` properties
7. **System node properties** — per-node SELECT for `Contains` properties

**Return shape:**
```json
{
  "nodes": { "<uri>": { "propHash": { "VERTEX_TYPE_PROPERTY": "DataObject"|"System", ... } } },
  "edges": [ { "uri": "...", "source": "...", "target": "...", "propHash": { "EDGE_TYPE": "Provide"|"Relation", ... } } ],
  "title": "What is the network of systems for this data?",
  "dataMakerName": "GraphDataModel",
  "layout": "prerna.ui.components.specific.tap.InterfaceGraphPlaySheet"
}
```

---

## Debug Reactors

These reactors are not used in production UI flows. They are called from the SEMOSS Playground to validate parity between the new Java reactors and the legacy playsheets.

### `RunDebugSparqlReactor`

**Pixel call:**
```
RunDebugSparql(engineId=["6897e1e5-..."], query=["SELECT ..."], maxRows=[300])
```

| Key | Required | Default | Description |
|---|---|---|---|
| `engineId` | Yes | — | Target engine UUID |
| `query` | Yes | — | SPARQL SELECT query (rejects non-SELECT) |
| `maxRows` | No | 500 | Row cap |

**Return shape:**
```json
{ "engineId": "...", "rowCount": 847, "maxRows": 300, "truncated": true, "rows": [...] }
```

---

### `CompareGraphOutputsReactor`

**Pixel call:**
```
CompareGraphOutputs(database=["..."], dataObject=["http://.../DataObject/Admissions"])
```

Runs both the legacy `RunPlaysheetReactor` (insight `"140"`, param key `"Data"`) and the new `GetGraphForDataObjectReactor` programmatically, then diffs their node-URI sets and `source|target` edge key sets.

**Edge extraction** handles both `SEMOSSEdge` objects (legacy output) and plain `Map` objects (new output) via `instanceof` check.

**Return shape:**
```json
{
  "dataObject": "Admissions", "dataObjectUri": "...",
  "legacy": { "nodes": 12, "edges": 8 },
  "new":    { "nodes": 12, "edges": 8 },
  "match": true,
  "nodesOnlyInLegacy": [...], "nodesOnlyInNew": [...],
  "edgesOnlyInLegacy": [...], "edgesOnlyInNew": [...]
}
```

---

### `CompareCompositionTimelineReactor`

**Pixel call:**
```
CompareCompositionTimeline(systemUri=["http://.../ActiveSystem/MHS_GENESIS"])
```

| Key | Required | Default | Description |
|---|---|---|---|
| `systemUri` | Yes | — | Focal system |
| `legacyDatabase` | No | `decommissionEngineId` | Override legacy home engine |

The most comprehensive debug reactor. Validates `GetCompositionTimelineReactor` against legacy insight #21 across three comparison layers:

1. **Structural nodes** — URI key sets from both `"nodes"` maps
2. **Structural edges** — `source|target` string sets from both `"edges"` collections
3. **TimeHash overlay** — three sub-comparisons:
   - `timeHashOnlyInLegacy` / `timeHashOnlyInNew` — nodes with timeHash in one output but not the other
   - `timeHashPhaseMismatch` — nodes present in both but with different phase key sets
   - `timeHashValueMismatch` — nodes with identical phase keys but different values

**Normalization:** `normalizePhaseData()` canonicalizes LOE as `Double.toString(Double.parseDouble(...))`, `dependICDS` as a sorted JSON array of local names, and string fields stripped via `unwrapLiteral()` + `localName()`. This prevents false mismatches from literal datatype annotations (e.g., `"12.0"^^xsd:double`) produced by the legacy wrapper.

**Return shape:**
```json
{
  "system": "MHS_GENESIS", "systemUri": "...",
  "legacy": { "nodes": 47, "edges": 38, "timeHashNodes": 12 },
  "new":    { "nodes": 47, "edges": 38, "timeHashNodes": 12 },
  "structuralMatch": true, "timeHashMatch": true, "match": true,
  "legacyOverlayValues": { "<uri>": { "Decommissioned": { ... } } },
  "timeHashPhaseDiffs": { "ICD_Name": { "legacyPhases": [...], "newPhases": [...] } },
  "timeHashValueDiffs": { "ICD_Name": { "Requirements": { "legacy": {...}, "new": {...} } } }
}
```

The `legacyOverlayValues` and `legacyOverlaySummary` fields are always emitted (even when `match=true`) for stability inspection across runs.

---

### `InspectLOEOverlayReactor`

**Pixel call:**
```
InspectLOEOverlay(systemUri=["http://.../ActiveSystem/ACS_DAL"], maxRows=[800])
```

| Key | Required | Default | Description |
|---|---|---|---|
| `systemUri` | Yes | — | Focal system |
| `maxRows` | No | 600 | Row cap on the `rows` list |

**Purpose:** Diagnoses LOE phase overlay **ambiguity**. The Q1 query's `GROUP BY` uses last-write-wins when multiple TransitionGLItem rows target the same `(futureICD, phase)`. This reactor surfaces those "ambiguous groups."

**Execution:**
1. Calls `GetCompositionTimelineReactor` programmatically to get in-scope ICD URIs
2. Runs a non-aggregated version of the LOE query against `transitionEngineId`
3. Filters to scoped ICDs only
4. Groups by `(futureICD, phase)` — groups with `candidateCount > 1` are ambiguous
5. Sorts ambiguous groups descending by `candidateCount`

**Return shape:**
```json
{
  "systemUri": "...", "system": "ACS_DAL", "transitionEngineId": "...",
  "scopedICDCount": 23, "rawRowCount": 1400, "candidateRowCount": 87,
  "ambiguousGroupCount": 4, "groupsEvaluated": 31,
  "ambiguousGroups": [
    { "futureICD": "...", "futureICDLabel": "...", "phase": "Requirements",
      "candidateCount": 3, "glTags": [...], "influencerSystems": [...],
      "glItems": [...], "loeValues": [12.0, 24.0], "candidates": [...] }
  ],
  "rowsTruncated": false, "rows": [...]
}
```

---

## Inter-Reactor Dependencies

```
CompareCompositionTimelineReactor
  ├── GetCompositionTimelineReactor  (programmatic call)
  └── RunPlaysheetReactor (legacy insight #21, SEMOSS core)

InspectLOEOverlayReactor
  └── GetCompositionTimelineReactor  (programmatic call, to get ICD scope)

CompareGraphOutputsReactor
  ├── GetGraphForDataObjectReactor   (programmatic call)
  └── RunPlaysheetReactor (legacy insight #140, SEMOSS core)
```

Production reactors (`GetCompositionTimeline`, `ListActiveSystems`, `GetGraphForDataObject`, `ListDataObjects`) have **no reactor-to-reactor dependencies** — they operate independently through `QueryExecutor` and `ProjectProperties`.

---

## SEMOSS Ontology URI Reference

All queries use the `http://semoss.org/ontologies/` namespace.

| Concept | URI |
|---|---|
| ActiveSystem | `http://semoss.org/ontologies/Concept/ActiveSystem` |
| SystemInterface (ICD) | `http://semoss.org/ontologies/Concept/SystemInterface` |
| DataObject | `http://semoss.org/ontologies/Concept/DataObject` |
| ProposedDecommissionedSystemInterface | `http://semoss.org/ontologies/Concept/ProposedDecommissionedSystemInterface` |
| TransitionGLItem | `http://semoss.org/ontologies/Concept/TransitionGLItem` |
| SDLCPhase | `http://semoss.org/ontologies/Concept/SDLCPhase` |
| GLTag | `http://semoss.org/ontologies/Concept/GLTag` |
| Provide | `http://semoss.org/ontologies/Relation/Provide` |
| Consume | `http://semoss.org/ontologies/Relation/Consume` |
| Payload | `http://semoss.org/ontologies/Relation/Payload` |
| Contains | `http://semoss.org/ontologies/Relation/Contains` |
| Contains/LOEcalc | `http://semoss.org/ontologies/Relation/Contains/LOEcalc` |
| Contains/CRM | `http://semoss.org/ontologies/Relation/Contains/CRM` |
| Contains/Probability_of_Included_BoS_Enterprise_EHRS | `http://semoss.org/ontologies/Relation/Contains/Probability_of_Included_BoS_Enterprise_EHRS` |
| TaggedBy | `http://semoss.org/ontologies/Relation/TaggedBy` |
| Influences | `http://semoss.org/ontologies/Relation/Influences` |
| BelongsTo | `http://semoss.org/ontologies/Relation/BelongsTo` |
| Output | `http://semoss.org/ontologies/Relation/Output` |

---

## Key Developer Notes

1. **CONSTRUCT vs SELECT:** Only `GetCompositionTimelineReactor` uses CONSTRUCT. It accesses the engine directly via `executor.getEngine()` and `WrapperManager.getCWrapper()`. All other queries go through `QueryExecutor.executeSelect()`.

2. **Literal normalization:** SPARQL wrapper values can arrive as `"12.0"^^<xsd:double>` or `"Requirements"@en`. The `unwrapLiteral()` method strips type annotations, language tags, and quote wrapping. Currently duplicated in `GetCompositionTimelineReactor` and `CompareCompositionTimelineReactor` — move to `HelperMethods` if shared.

3. **Last-write-wins on timeHash:** The Q1 LOE overlay uses `GROUP BY` + `GROUP_CONCAT` to collapse candidates into one row per `(futureICD, phase)`. Within a group, `LOE`, `GLitem`, and `gltag` come from whichever SPARQL row is returned last (non-deterministic). `InspectLOEOverlayReactor` surfaces this drift.

4. **Engine override pattern:** `GetCompositionTimelineReactor` supports Pixel parameter overrides with `project.properties` fallback. Other reactors take `database` as a required parameter without fallback, or read directly from `ProjectProperties`.

5. **`ProjectProperties` is process-scoped:** Never invalidated. Engine ID changes require a process restart (or manual null-reset of `INSTANCE` via code change).

6. **Pixel naming convention:** Drop the `Reactor` suffix when calling from the frontend or Playground. `GetCompositionTimelineReactor` → `GetCompositionTimeline(...)`.

7. **`Constants.java` and `HelperMethods.java`** are empty scaffolding. All constants and helpers currently live as private statics within each reactor.

8. **Non-fatal overlays:** Stages 2–5 in `GetCompositionTimelineReactor` use `safeExecute()` — overlay failures are logged and swallowed so the base graph is always returned even if an overlay engine is unavailable.
