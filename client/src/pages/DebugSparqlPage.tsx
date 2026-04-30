import { useCallback, useEffect, useMemo, useState } from "react";
import { runPixel } from "@semoss/sdk";
import { useInsight } from "@semoss/sdk/react";

interface SystemOption {
  uri: string;
  label: string;
}

interface InspectOutput {
  system: string;
  systemUri: string;
  transitionEngineId: string;
  scopedICDCount: number;
  rawRowCount: number;
  candidateRowCount: number;
  ambiguousGroupCount: number;
  groupsEvaluated: number;
  rowsTruncated: boolean;
  rows: Array<Record<string, unknown>>;
  ambiguousGroups: Array<Record<string, unknown>>;
}

interface SparqlOutput {
  engineId: string;
  rowCount: number;
  maxRows: number;
  truncated: boolean;
  rows: Array<Record<string, string>>;
}

const ENGINE_OPTIONS = [
  { label: "TAP_Core_Data (base)", value: "133db94b-4371-4763-bff9-edf7e5ed021b" },
  { label: "FutureDB (decommission)", value: "df69df03-45f6-483a-af34-9c4d20bb6b7c" },
  { label: "FutureCostDB (transition)", value: "6897e1e5-3604-464d-bf26-c675eac23d26" },
];

const DEFAULT_LOE_QUERY = `SELECT DISTINCT ?sys ?futureICD ?phase ?GLitem (ROUND(?loe) AS ?LOE) ?gltag ?oldICD
WHERE {
  {?subclass <http://www.w3.org/2000/01/rdf-schema#subClassOf>
      <http://semoss.org/ontologies/Concept/TransitionGLItem> ;}
  {?GLitem <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> ?subclass ;}
  {?tagged <http://www.w3.org/2000/01/rdf-schema#subPropertyOf>
      <http://semoss.org/ontologies/Relation/TaggedBy>;}
  {?gltag <http://www.w3.org/1999/02/22-rdf-syntax-ns#type>
      <http://semoss.org/ontologies/Concept/GLTag> ;}
  {?GLitem ?tagged ?gltag;}
  {?influences <http://www.w3.org/2000/01/rdf-schema#subPropertyOf>
      <http://semoss.org/ontologies/Relation/Influences>;}
  {?sys ?influences ?GLitem ;}
  {?GLitem <http://semoss.org/ontologies/Relation/Contains/LOEcalc> ?loe;}
  {?phase <http://www.w3.org/1999/02/22-rdf-syntax-ns#type>
      <http://semoss.org/ontologies/Concept/SDLCPhase> ;}
  {?belongs <http://www.w3.org/2000/01/rdf-schema#subPropertyOf>
      <http://semoss.org/ontologies/Relation/BelongsTo>;}
  {?GLitem ?belongs ?phase ;}
  {?futureICD <http://www.w3.org/1999/02/22-rdf-syntax-ns#type>
      <http://semoss.org/ontologies/Concept/SystemInterface> ;}
  {?output <http://www.w3.org/2000/01/rdf-schema#subPropertyOf>
      <http://semoss.org/ontologies/Relation/Output>;}
  {?GLitem ?output ?futureICD ;}
  OPTIONAL {
    {?input <http://www.w3.org/2000/01/rdf-schema#subPropertyOf>
        <http://semoss.org/ontologies/Relation/Input>;}
    {?oldICD ?input ?GLitem}
    {?oldICD <http://www.w3.org/1999/02/22-rdf-syntax-ns#type>
        <http://semoss.org/ontologies/Concept/SystemInterface> ;}
  }
}
ORDER BY ?futureICD ?phase ?GLitem`;

function escapePixelString(value: string): string {
  return value
    .replace(/\\/g, "\\\\")
    .replace(/"/g, '\\"')
    .replace(/\r\n|\n|\r/g, "\\n");
}

function short(value: unknown): string {
  const text = String(value ?? "");
  const slash = text.lastIndexOf("/");
  const hash = text.lastIndexOf("#");
  const idx = Math.max(slash, hash);
  return idx >= 0 ? text.substring(idx + 1) : text;
}

export const DebugSparqlPage = () => {
  const { insightId } = useInsight();

  const [systems, setSystems] = useState<SystemOption[]>([]);
  const [selectedSystem, setSelectedSystem] = useState("");
  const [maxRows, setMaxRows] = useState(600);
  const [inspectLoading, setInspectLoading] = useState(false);
  const [inspectError, setInspectError] = useState<string | null>(null);
  const [inspectData, setInspectData] = useState<InspectOutput | null>(null);

  const [engineId, setEngineId] = useState(ENGINE_OPTIONS[2].value);
  const [query, setQuery] = useState(DEFAULT_LOE_QUERY);
  const [queryMaxRows, setQueryMaxRows] = useState(300);
  const [queryLoading, setQueryLoading] = useState(false);
  const [queryError, setQueryError] = useState<string | null>(null);
  const [queryData, setQueryData] = useState<SparqlOutput | null>(null);

  const loadSystems = useCallback(async () => {
    if (!insightId) return;
    const response = await runPixel("ListActiveSystems();", insightId);
    if (response.errors.length > 0) {
      throw new Error(response.errors.join(", "));
    }
    const output = response.pixelReturn[0]?.output;
    if (!Array.isArray(output)) return;
    const list = output as SystemOption[];
    setSystems(list);
    if (!selectedSystem && list.length > 0) {
      setSelectedSystem(list[0].uri);
    }
  }, [insightId, selectedSystem]);

  useEffect(() => {
    loadSystems().catch((err) => {
      setInspectError(err instanceof Error ? err.message : "Failed to load systems");
    });
  }, [loadSystems]);

  const runInspect = useCallback(async () => {
    if (!insightId || !selectedSystem) return;
    setInspectLoading(true);
    setInspectError(null);
    try {
      const pixel = `InspectLOEOverlay(systemUri=[\"${selectedSystem}\"], maxRows=[${maxRows}]);`;
      const response = await runPixel(pixel, insightId);
      if (response.errors.length > 0) {
        throw new Error(response.errors.join(", "));
      }
      setInspectData(response.pixelReturn[0]?.output as InspectOutput);
    } catch (err) {
      setInspectError(err instanceof Error ? err.message : "Failed to inspect LOE overlay");
      setInspectData(null);
    } finally {
      setInspectLoading(false);
    }
  }, [insightId, maxRows, selectedSystem]);

  const runAdHocQuery = useCallback(async () => {
    if (!insightId) return;
    setQueryLoading(true);
    setQueryError(null);
    try {
      const pixel = `RunDebugSparql(engineId=[\"${engineId}\"], query=[\"${escapePixelString(query)}\"], maxRows=[${queryMaxRows}]);`;
      const response = await runPixel(pixel, insightId);
      if (response.errors.length > 0) {
        throw new Error(response.errors.join(", "));
      }
      setQueryData(response.pixelReturn[0]?.output as SparqlOutput);
    } catch (err) {
      setQueryError(err instanceof Error ? err.message : "Failed to run query");
      setQueryData(null);
    } finally {
      setQueryLoading(false);
    }
  }, [engineId, insightId, query, queryMaxRows]);

  const queryColumns = useMemo(() => {
    if (!queryData || queryData.rows.length === 0) return [] as string[];
    return Object.keys(queryData.rows[0]);
  }, [queryData]);

  return (
    <div className="h-full overflow-auto bg-gray-50 p-6 space-y-6">
      <div>
        <h1 className="text-xl font-semibold text-gray-900">Debug: SPARQL + LOE Candidate Inspector</h1>
        <p className="text-sm text-gray-600 mt-1">
          Use this page to inspect ambiguous LOE candidates for one system and run ad-hoc SELECT queries.
        </p>
      </div>

      <section className="bg-white border border-gray-200 rounded-lg p-4 space-y-4">
        <div className="flex items-center justify-between">
          <h2 className="text-base font-semibold text-gray-900">LOE Collision Inspector</h2>
          <button
            type="button"
            onClick={runInspect}
            disabled={inspectLoading || !selectedSystem}
            className="px-3 py-1.5 text-sm rounded bg-blue-600 text-white hover:bg-blue-700 disabled:opacity-50"
          >
            {inspectLoading ? "Running..." : "Inspect"}
          </button>
        </div>

        <div className="grid grid-cols-1 md:grid-cols-3 gap-3">
          <label className="text-sm text-gray-700">
            System
            <select
              value={selectedSystem}
              onChange={(e) => setSelectedSystem(e.target.value)}
              className="mt-1 w-full border border-gray-300 rounded px-2 py-1.5"
            >
              {systems.map((system) => (
                <option key={system.uri} value={system.uri}>{system.label}</option>
              ))}
            </select>
          </label>

          <label className="text-sm text-gray-700">
            Max rows to return
            <input
              type="number"
              value={maxRows}
              min={50}
              onChange={(e) => setMaxRows(Number(e.target.value) || 600)}
              className="mt-1 w-full border border-gray-300 rounded px-2 py-1.5"
            />
          </label>
        </div>

        {inspectError && (
          <div className="text-sm text-red-700 bg-red-50 border border-red-200 rounded p-2">{inspectError}</div>
        )}

        {inspectData && (
          <div className="space-y-3">
            <div className="grid grid-cols-2 md:grid-cols-4 gap-2 text-xs">
              <Metric label="Scoped ICDs" value={inspectData.scopedICDCount} />
              <Metric label="Candidate rows" value={inspectData.candidateRowCount} />
              <Metric label="Groups" value={inspectData.groupsEvaluated} />
              <Metric label="Ambiguous groups" value={inspectData.ambiguousGroupCount} />
            </div>

            <div>
              <h3 className="text-sm font-semibold text-gray-800 mb-1">Ambiguous groups (candidateCount &gt; 1)</h3>
              <div className="max-h-64 overflow-auto border border-gray-200 rounded">
                <table className="w-full text-xs">
                  <thead className="sticky top-0 bg-gray-100">
                    <tr className="text-left">
                      <th className="px-2 py-1">ICD</th>
                      <th className="px-2 py-1">Phase</th>
                      <th className="px-2 py-1">Candidates</th>
                      <th className="px-2 py-1">Tags</th>
                      <th className="px-2 py-1">Influencers</th>
                    </tr>
                  </thead>
                  <tbody>
                    {inspectData.ambiguousGroups.map((group, idx) => (
                      <tr key={`${String(group.futureICD)}-${String(group.phase)}-${idx}`} className="border-t border-gray-100">
                        <td className="px-2 py-1 font-mono">{short(group.futureICDLabel)}</td>
                        <td className="px-2 py-1">{String(group.phase)}</td>
                        <td className="px-2 py-1">{String(group.candidateCount)}</td>
                        <td className="px-2 py-1">{Array.isArray(group.glTags) ? group.glTags.join(", ") : ""}</td>
                        <td className="px-2 py-1">{Array.isArray(group.influencerSystems) ? group.influencerSystems.join(", ") : ""}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </div>
          </div>
        )}
      </section>

      <section className="bg-white border border-gray-200 rounded-lg p-4 space-y-4">
        <div className="flex items-center justify-between">
          <h2 className="text-base font-semibold text-gray-900">Ad-hoc SPARQL (SELECT only)</h2>
          <button
            type="button"
            onClick={runAdHocQuery}
            disabled={queryLoading}
            className="px-3 py-1.5 text-sm rounded bg-gray-800 text-white hover:bg-black disabled:opacity-50"
          >
            {queryLoading ? "Running..." : "Run Query"}
          </button>
        </div>

        <div className="grid grid-cols-1 md:grid-cols-3 gap-3">
          <label className="text-sm text-gray-700">
            Engine
            <select
              value={engineId}
              onChange={(e) => setEngineId(e.target.value)}
              className="mt-1 w-full border border-gray-300 rounded px-2 py-1.5"
            >
              {ENGINE_OPTIONS.map((opt) => (
                <option key={opt.value} value={opt.value}>{opt.label}</option>
              ))}
            </select>
          </label>

          <label className="text-sm text-gray-700">
            Max rows
            <input
              type="number"
              value={queryMaxRows}
              min={50}
              onChange={(e) => setQueryMaxRows(Number(e.target.value) || 300)}
              className="mt-1 w-full border border-gray-300 rounded px-2 py-1.5"
            />
          </label>
        </div>

        <label className="text-sm text-gray-700 block">
          SPARQL query
          <textarea
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            rows={14}
            className="mt-1 w-full border border-gray-300 rounded p-2 font-mono text-xs"
          />
        </label>

        {queryError && (
          <div className="text-sm text-red-700 bg-red-50 border border-red-200 rounded p-2">{queryError}</div>
        )}

        {queryData && (
          <div className="space-y-2">
            <div className="text-xs text-gray-600">
              {queryData.rowCount} row(s) returned{queryData.truncated ? `, showing first ${queryData.maxRows}` : ""}.
            </div>
            <div className="max-h-80 overflow-auto border border-gray-200 rounded">
              <table className="w-full text-xs">
                <thead className="sticky top-0 bg-gray-100">
                  <tr>
                    {queryColumns.map((col) => (
                      <th key={col} className="text-left px-2 py-1 font-semibold">{col}</th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {queryData.rows.map((row, idx) => (
                    <tr key={idx} className="border-t border-gray-100">
                      {queryColumns.map((col) => (
                        <td key={`${idx}-${col}`} className="px-2 py-1 align-top font-mono break-all">{row[col] ?? ""}</td>
                      ))}
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>
        )}
      </section>
    </div>
  );
};

const Metric = ({ label, value }: { label: string; value: number }) => (
  <div className="border border-gray-200 rounded p-2 bg-gray-50">
    <div className="text-[11px] text-gray-500 uppercase">{label}</div>
    <div className="text-lg font-semibold text-gray-900">{value}</div>
  </div>
);
