package reactors.debug;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import prerna.om.SEMOSSEdge;
import prerna.om.SEMOSSVertex;
import prerna.reactor.PixelPlanner;
import prerna.reactor.legacy.playsheets.RunPlaysheetReactor;
import prerna.sablecc2.om.GenRowStruct;
import prerna.sablecc2.om.PixelDataType;
import prerna.sablecc2.om.ReactorKeysEnum;
import prerna.sablecc2.om.nounmeta.NounMetadata;
import reactors.AbstractProjectReactor;
import reactors.compositionTimeline.GetCompositionTimelineReactor;

/**
 * Debug reactor that compares legacy insight #21 ("How does the composition of this
 * system change over time?") against the new {@link GetCompositionTimelineReactor}
 * for a single ActiveSystem.
 *
 * <p>Three comparison layers are performed:
 * <ol>
 *   <li><b>Structural nodes</b> – URI sets of all nodes in each output.</li>
 *   <li><b>Structural edges</b> – source|target key sets of all edges.</li>
 *   <li><b>TimeHash overlay</b> – which nodes carry a timeHash, and which phase keys
 *       each carries (Decommissioned / Requirements / Design / Develop / Test / etc.).</li>
 * </ol>
 *
 * <p>Pixel call:
 * <pre>
 *   CompareCompositionTimeline(
 *     systemUri=["http://health.mil/ontologies/Concept/ActiveSystem/MHS_GENESIS"]
 *   );
 * </pre>
 *
 * <p>An optional {@code legacyDatabase} override can be passed to change the home
 * engine for the legacy playsheet call (defaults to {@code project.properties#decommissionEngineId}
 * which is FutureDB — the project engine for insight #21).
 */
public class CompareCompositionTimelineReactor extends AbstractProjectReactor {

  private static final Logger LOGGER =
      LogManager.getLogger(CompareCompositionTimelineReactor.class);

  private static final String SYSTEM_URI_KEY = "systemUri";
  private static final String LEGACY_ENGINE_KEY = "legacyDatabase";
  private static final String LEGACY_INSIGHT_ID = "21";

  public CompareCompositionTimelineReactor() {
    this.keysToGet = new String[] { SYSTEM_URI_KEY, LEGACY_ENGINE_KEY };
    this.keyRequired = new int[] { 1, 0 };
  }

  @Override
  @SuppressWarnings("unchecked")
  protected NounMetadata doExecute() {
    String systemUri = this.keyValue.get(SYSTEM_URI_KEY);
    if (systemUri == null || systemUri.trim().isEmpty()) {
      throw new IllegalArgumentException("systemUri parameter is required");
    }

    // Legacy home engine = FutureDB (df69df03) — the project engine for insight #21
    String legacyEngineId = this.keyValue.get(LEGACY_ENGINE_KEY);
    if (legacyEngineId == null || legacyEngineId.trim().isEmpty()) {
      legacyEngineId = projectProperties.getDecommissionEngineId();
    }

    String systemName = systemUri.contains("/")
        ? systemUri.substring(systemUri.lastIndexOf('/') + 1)
        : systemUri;

    LOGGER.info("CompareCompositionTimeline: system={} ({})", systemName, systemUri);

    Map<String, Object> result = new LinkedHashMap<>();
    result.put("system", systemName);
    result.put("systemUri", systemUri);

    // ── 1. Run legacy insight #21 via RunPlaysheetReactor ────────────────────
    Map<String, Object> legacyResult;
    try {
      legacyResult = runLegacyInsight(legacyEngineId, systemUri);
    } catch (Exception e) {
      LOGGER.error("Legacy insight #21 failed for " + systemName, e);
      result.put("error", "Legacy insight failed: " + e.getMessage());
      result.put("match", false);
      result.put("structuralMatch", false);
      result.put("timeHashMatch", false);
      return new NounMetadata(result, PixelDataType.CUSTOM_DATA_STRUCTURE);
    }

    // ── 2. Run new GetCompositionTimelineReactor ─────────────────────────────
    Map<String, Object> newResult;
    try {
      newResult = runNewReactor(systemUri);
    } catch (Exception e) {
      LOGGER.error("New reactor failed for " + systemName, e);
      result.put("error", "New reactor failed: " + e.getMessage());
      result.put("match", false);
      result.put("structuralMatch", false);
      result.put("timeHashMatch", false);
      return new NounMetadata(result, PixelDataType.CUSTOM_DATA_STRUCTURE);
    }

    // ── 3. Structural comparison ─────────────────────────────────────────────
    Set<String> legacyNodeUris = extractNodeUris(legacyResult);
    Set<String> newNodeUris    = extractNodeUris(newResult);
    Set<String> legacyEdgeKeys = extractEdgeKeys(legacyResult);
    Set<String> newEdgeKeys    = extractEdgeKeys(newResult);

    Set<String> nodesOnlyInLegacy = new TreeSet<>(legacyNodeUris);
    nodesOnlyInLegacy.removeAll(newNodeUris);

    Set<String> nodesOnlyInNew = new TreeSet<>(newNodeUris);
    nodesOnlyInNew.removeAll(legacyNodeUris);

    Set<String> edgesOnlyInLegacy = new TreeSet<>(legacyEdgeKeys);
    edgesOnlyInLegacy.removeAll(newEdgeKeys);

    Set<String> edgesOnlyInNew = new TreeSet<>(newEdgeKeys);
    edgesOnlyInNew.removeAll(legacyEdgeKeys);

    boolean structuralMatch = nodesOnlyInLegacy.isEmpty() && nodesOnlyInNew.isEmpty()
        && edgesOnlyInLegacy.isEmpty() && edgesOnlyInNew.isEmpty();

    // ── 4. TimeHash / overlay comparison ─────────────────────────────────────
    // URI → Set<phaseName> for every node that has a non-empty timeHash
    Map<String, Set<String>> legacyTimeHash = extractTimeHashPhases(legacyResult);
    Map<String, Set<String>> newTimeHash    = extractTimeHashPhases(newResult);
    // URI → phaseName → normalized phase payload map
    Map<String, Map<String, Map<String, String>>> legacyTimeHashValues =
      extractTimeHashValues(legacyResult);
    Map<String, Map<String, Map<String, String>>> newTimeHashValues =
      extractTimeHashValues(newResult);

    // Always expose the full normalized legacy overlay payload so repeated runs
    // can be compared directly for stability, independent of mismatch-only views.
    result.put("legacyOverlayValues", legacyTimeHashValues);
    result.put("legacyOverlaySummary", buildOverlaySummary(legacyTimeHashValues));

    // Nodes with overlay in legacy but not in new (overlay missing from new reactor)
    Set<String> timeHashOnlyInLegacy = new TreeSet<>(legacyTimeHash.keySet());
    timeHashOnlyInLegacy.removeAll(newTimeHash.keySet());

    // Nodes with overlay in new but not in legacy (extra overlay in new reactor)
    Set<String> timeHashOnlyInNew = new TreeSet<>(newTimeHash.keySet());
    timeHashOnlyInNew.removeAll(legacyTimeHash.keySet());

    // Nodes present in both but with different phase key sets
    Set<String> timeHashPhaseMismatch = new TreeSet<>();
    Map<String, Object> timeHashPhaseDiffs = new LinkedHashMap<>();
    // Nodes present in both with same phase keys but different phase payload values
    Set<String> timeHashValueMismatch = new TreeSet<>();
    Map<String, Object> timeHashValueDiffs = new LinkedHashMap<>();

    Set<String> commonTimeHash = new TreeSet<>(legacyTimeHash.keySet());
    commonTimeHash.retainAll(newTimeHash.keySet());
    for (String uri : commonTimeHash) {
      Set<String> legacyPhases = legacyTimeHash.get(uri);
      Set<String> newPhases    = newTimeHash.get(uri);
      if (!legacyPhases.equals(newPhases)) {
        timeHashPhaseMismatch.add(uri);

        Set<String> phasesOnlyInLegacy = new TreeSet<>(legacyPhases);
        phasesOnlyInLegacy.removeAll(newPhases);

        Set<String> phasesOnlyInNew = new TreeSet<>(newPhases);
        phasesOnlyInNew.removeAll(legacyPhases);

        Map<String, Object> phaseDiff = new LinkedHashMap<>();
        phaseDiff.put("legacyPhases", new ArrayList<>(legacyPhases));
        phaseDiff.put("newPhases", new ArrayList<>(newPhases));
        phaseDiff.put("phasesOnlyInLegacy", new ArrayList<>(phasesOnlyInLegacy));
        phaseDiff.put("phasesOnlyInNew", new ArrayList<>(phasesOnlyInNew));
        timeHashPhaseDiffs.put(localName(uri), phaseDiff);
      } else {
        Map<String, Map<String, String>> legacyNodeValues = legacyTimeHashValues.get(uri);
        Map<String, Map<String, String>> newNodeValues = newTimeHashValues.get(uri);

        Set<String> phases = new TreeSet<>(legacyPhases);
        Map<String, Object> perPhaseDiff = new LinkedHashMap<>();
        for (String phase : phases) {
          Map<String, String> legacyPhaseValues =
              legacyNodeValues != null ? legacyNodeValues.get(phase) : null;
          Map<String, String> newPhaseValues =
              newNodeValues != null ? newNodeValues.get(phase) : null;

          if (legacyPhaseValues == null) legacyPhaseValues = new TreeMap<>();
          if (newPhaseValues == null) newPhaseValues = new TreeMap<>();

          if (!legacyPhaseValues.equals(newPhaseValues)) {
            Map<String, Object> valueDiff = new LinkedHashMap<>();
            valueDiff.put("legacy", legacyPhaseValues);
            valueDiff.put("new", newPhaseValues);
            perPhaseDiff.put(phase, valueDiff);
          }
        }

        if (!perPhaseDiff.isEmpty()) {
          timeHashValueMismatch.add(uri);
          timeHashValueDiffs.put(localName(uri), perPhaseDiff);
        }
      }
    }

    boolean timeHashMatch = timeHashOnlyInLegacy.isEmpty()
        && timeHashOnlyInNew.isEmpty()
        && timeHashPhaseMismatch.isEmpty()
        && timeHashValueMismatch.isEmpty();

    boolean overallMatch = structuralMatch && timeHashMatch;

    // ── 5. Build response ────────────────────────────────────────────────────
    Map<String, Object> legacyCounts = new LinkedHashMap<>();
    legacyCounts.put("nodes", legacyNodeUris.size());
    legacyCounts.put("edges", legacyEdgeKeys.size());
    legacyCounts.put("timeHashNodes", legacyTimeHash.size());
    result.put("legacy", legacyCounts);

    Map<String, Object> newCounts = new LinkedHashMap<>();
    newCounts.put("nodes", newNodeUris.size());
    newCounts.put("edges", newEdgeKeys.size());
    newCounts.put("timeHashNodes", newTimeHash.size());
    result.put("new", newCounts);

    result.put("structuralMatch", structuralMatch);
    result.put("timeHashMatch", timeHashMatch);
    result.put("match", overallMatch);

    if (!nodesOnlyInLegacy.isEmpty()) {
      result.put("nodesOnlyInLegacy", new ArrayList<>(nodesOnlyInLegacy));
    }
    if (!nodesOnlyInNew.isEmpty()) {
      result.put("nodesOnlyInNew", new ArrayList<>(nodesOnlyInNew));
    }
    if (!edgesOnlyInLegacy.isEmpty()) {
      result.put("edgesOnlyInLegacy", new ArrayList<>(edgesOnlyInLegacy));
    }
    if (!edgesOnlyInNew.isEmpty()) {
      result.put("edgesOnlyInNew", new ArrayList<>(edgesOnlyInNew));
    }
    if (!timeHashOnlyInLegacy.isEmpty()) {
      result.put("timeHashOnlyInLegacy", new ArrayList<>(timeHashOnlyInLegacy));
    }
    if (!timeHashOnlyInNew.isEmpty()) {
      result.put("timeHashOnlyInNew", new ArrayList<>(timeHashOnlyInNew));
    }
    if (!timeHashPhaseMismatch.isEmpty()) {
      result.put("timeHashPhaseMismatch", new ArrayList<>(timeHashPhaseMismatch));
      result.put("timeHashPhaseDiffs", timeHashPhaseDiffs);
    }
    if (!timeHashValueMismatch.isEmpty()) {
      result.put("timeHashValueMismatch", new ArrayList<>(timeHashValueMismatch));
      result.put("timeHashValueDiffs", timeHashValueDiffs);
    }

    LOGGER.info(
        "CompareCompositionTimeline [{}]: match={} structural={} timeHash={} "
        + "legacy(N={} E={} TH={}) new(N={} E={} TH={}) "
        + "nodesOnlyLegacy={} nodesOnlyNew={} "
        + "edgesOnlyLegacy={} edgesOnlyNew={} "
        + "thOnlyLegacy={} thOnlyNew={} phaseMismatch={} valueMismatch={}",
        systemName, overallMatch, structuralMatch, timeHashMatch,
        legacyNodeUris.size(), legacyEdgeKeys.size(), legacyTimeHash.size(),
        newNodeUris.size(), newEdgeKeys.size(), newTimeHash.size(),
        nodesOnlyInLegacy.size(), nodesOnlyInNew.size(),
        edgesOnlyInLegacy.size(), edgesOnlyInNew.size(),
        timeHashOnlyInLegacy.size(), timeHashOnlyInNew.size(),
        timeHashPhaseMismatch.size(), timeHashValueMismatch.size());

    return new NounMetadata(result, PixelDataType.CUSTOM_DATA_STRUCTURE);
  }

  // ──────────────────────────────────────────────────────────────────────────
  // LEGACY INSIGHT RUNNER
  // ──────────────────────────────────────────────────────────────────────────

  /**
   * Invokes legacy insight #21 via {@link RunPlaysheetReactor}.
   * Home engine = FutureDB (df69df03-...) — the project engine for insight #21.
   * Param key = "System" — matches the filter pre-transformation header in OldInsight.
   */
  @SuppressWarnings("unchecked")
  private Map<String, Object> runLegacyInsight(String engineId, String systemUri) {
    RunPlaysheetReactor playsheetReactor = new RunPlaysheetReactor();
    playsheetReactor.In();
    playsheetReactor.setInsight(this.insight);

    PixelPlanner planner = new PixelPlanner();
    planner.setVarStore(this.insight.getVarStore());
    playsheetReactor.setPixelPlanner(planner);

    // database / app (legacy fallback alias)
    GenRowStruct grsEngine = new GenRowStruct();
    grsEngine.add(new NounMetadata(engineId, PixelDataType.CONST_STRING));
    playsheetReactor.getNounStore().addNoun(ReactorKeysEnum.DATABASE.getKey(), grsEngine);
    playsheetReactor.getNounStore().addNoun("app", grsEngine);

    // insight id = "21"
    GenRowStruct grsId = new GenRowStruct();
    grsId.add(new NounMetadata(LEGACY_INSIGHT_ID, PixelDataType.CONST_STRING));
    playsheetReactor.getNounStore().addNoun(ReactorKeysEnum.ID.getKey(), grsId);

    // param key "System" = the filter pre-transformation header for insight #21
    Map<String, List<Object>> params = new HashMap<>();
    List<Object> systemList = new ArrayList<>();
    systemList.add(systemUri);
    params.put("System", systemList);

    GenRowStruct grsParams = new GenRowStruct();
    grsParams.add(new NounMetadata(params, PixelDataType.MAP));
    playsheetReactor.getNounStore().addNoun(ReactorKeysEnum.PARAM_KEY.getKey(), grsParams);

    NounMetadata legacyNoun = playsheetReactor.execute();
    return (Map<String, Object>) legacyNoun.getValue();
  }

  // ──────────────────────────────────────────────────────────────────────────
  // NEW REACTOR RUNNER
  // ──────────────────────────────────────────────────────────────────────────

  /**
   * Invokes {@link GetCompositionTimelineReactor} programmatically, wiring the same
   * {@code systemUri} noun that the Pixel call would provide.
   */
  @SuppressWarnings("unchecked")
  private Map<String, Object> runNewReactor(String systemUri) {
    GetCompositionTimelineReactor reactor = new GetCompositionTimelineReactor();
    reactor.In();
    reactor.setInsight(this.insight);

    PixelPlanner planner = new PixelPlanner();
    planner.setVarStore(this.insight.getVarStore());
    reactor.setPixelPlanner(planner);

    GenRowStruct grsSystem = new GenRowStruct();
    grsSystem.add(new NounMetadata(systemUri, PixelDataType.CONST_STRING));
    reactor.getNounStore().addNoun("systemUri", grsSystem);

    NounMetadata newNoun = reactor.execute();
    return (Map<String, Object>) newNoun.getValue();
  }

  // ──────────────────────────────────────────────────────────────────────────
  // EXTRACTION HELPERS
  // ──────────────────────────────────────────────────────────────────────────

  /** Extracts the node URI key set from the top-level "nodes" map in a result. */
  @SuppressWarnings("unchecked")
  private Set<String> extractNodeUris(Map<String, Object> resultMap) {
    Set<String> uris = new TreeSet<>();
    Object nodesObj = resultMap.get("nodes");
    if (nodesObj instanceof Map) {
      uris.addAll(((Map<String, ?>) nodesObj).keySet());
    }
    return uris;
  }

  /**
   * Extracts "source|target" key strings from the top-level "edges" collection.
   * Handles both {@link SEMOSSEdge} objects (legacy playsheet output) and plain
   * {@link Map} objects (new reactor output).
   */
  @SuppressWarnings("unchecked")
  private Set<String> extractEdgeKeys(Map<String, Object> resultMap) {
    Set<String> keys = new TreeSet<>();
    Object edgesObj = resultMap.get("edges");
    if (!(edgesObj instanceof Collection)) return keys;

    for (Object edgeObj : (Collection<?>) edgesObj) {
      String source = null;
      String target = null;

      if (edgeObj instanceof SEMOSSEdge) {
        SEMOSSEdge e = (SEMOSSEdge) edgeObj;
        source = e.outVertex != null ? e.outVertex.getURI() : null;
        target = e.inVertex  != null ? e.inVertex.getURI()  : null;
      } else if (edgeObj instanceof Map) {
        Map<String, Object> e = (Map<String, Object>) edgeObj;
        source = toStr(e.get("source"));
        target = toStr(e.get("target"));
      }

      if (source != null && target != null) {
        keys.add(source + "|" + target);
      }
    }
    return keys;
  }

  /**
   * Walks the "nodes" map and returns { nodeUri → Set&lt;phaseName&gt; } for every
   * node whose {@code propHash.timeHash} is a non-empty map.
   * Works on both the legacy (SEMOSSVertex propHash flattened) and new reactor
   * (plain nested Map) output formats.
   */
  @SuppressWarnings("unchecked")
  private Map<String, Set<String>> extractTimeHashPhases(Map<String, Object> resultMap) {
    Map<String, Set<String>> out = new TreeMap<>();
    Object nodesObj = resultMap.get("nodes");
    if (!(nodesObj instanceof Map)) return out;

    for (Map.Entry<String, Object> entry : ((Map<String, Object>) nodesObj).entrySet()) {
      String uri = entry.getKey();
      Object value = entry.getValue();

      Map<String, Object> timeHash = null;

      if (value instanceof SEMOSSVertex) {
        // Legacy output: SEMOSSVertex stores properties in propHash Hashtable
        Object thObj = ((SEMOSSVertex) value).propHash.get("timeHash");
        if (thObj instanceof Map) {
          timeHash = (Map<String, Object>) thObj;
        }
      } else if (value instanceof Map) {
        // New reactor output: nested Map structure with propHash.timeHash
        Map<String, Object> nodeMap = (Map<String, Object>) value;
        Object propHashObj = nodeMap.get("propHash");
        if (propHashObj instanceof Map) {
          Object thObj = ((Map<String, Object>) propHashObj).get("timeHash");
          if (thObj instanceof Map) {
            timeHash = (Map<String, Object>) thObj;
          }
        }
      }

      if (timeHash != null && !timeHash.isEmpty()) {
        Set<String> phases = new TreeSet<>();
        for (String phase : timeHash.keySet()) {
          phases.add(localName(unwrapLiteral(phase)));
        }
        out.put(uri, phases);
      }
    }
    return out;
  }

  /**
   * Returns normalized timeHash values for deep comparison.
   * Shape: { nodeUri -> { phase -> { phase, LOE, dependICDS, GLitem, gltag } } }
   */
  @SuppressWarnings("unchecked")
  private Map<String, Map<String, Map<String, String>>> extractTimeHashValues(
      Map<String, Object> resultMap) {
    Map<String, Map<String, Map<String, String>>> out = new TreeMap<>();
    Object nodesObj = resultMap.get("nodes");
    if (!(nodesObj instanceof Map)) return out;

    for (Map.Entry<String, Object> entry : ((Map<String, Object>) nodesObj).entrySet()) {
      String uri = entry.getKey();
      Object value = entry.getValue();

      Map<String, Object> timeHash = null;

      if (value instanceof SEMOSSVertex) {
        Object thObj = ((SEMOSSVertex) value).propHash.get("timeHash");
        if (thObj instanceof Map) {
          timeHash = (Map<String, Object>) thObj;
        }
      } else if (value instanceof Map) {
        Map<String, Object> nodeMap = (Map<String, Object>) value;
        Object propHashObj = nodeMap.get("propHash");
        if (propHashObj instanceof Map) {
          Object thObj = ((Map<String, Object>) propHashObj).get("timeHash");
          if (thObj instanceof Map) {
            timeHash = (Map<String, Object>) thObj;
          }
        }
      }

      if (timeHash == null || timeHash.isEmpty()) continue;

      Map<String, Map<String, String>> phaseMap = new TreeMap<>();
      for (Map.Entry<String, Object> phaseEntry : timeHash.entrySet()) {
        String phaseName = localName(unwrapLiteral(phaseEntry.getKey()));
        phaseMap.put(phaseName, normalizePhaseData(phaseEntry.getValue(), phaseName));
      }

      if (!phaseMap.isEmpty()) out.put(uri, phaseMap);
    }

    return out;
  }

  @SuppressWarnings("unchecked")
  private Map<String, String> normalizePhaseData(Object phaseObj, String phaseKey) {
    Map<String, String> out = new TreeMap<>();
    out.put("phase", phaseKey);
    out.put("LOE", "0.0");
    out.put("dependICDS", "[\"\"]");
    out.put("GLitem", "");
    out.put("gltag", "");

    if (!(phaseObj instanceof Map)) return out;
    Map<String, Object> p = (Map<String, Object>) phaseObj;

    out.put("phase", localName(unwrapLiteral(toStr(p.getOrDefault("phase", phaseKey)))));
    out.put("LOE", normalizeNumber(toStr(p.get("LOE"))));
    out.put("dependICDS", normalizeDependList(toStr(p.get("dependICDS"))));
    out.put("GLitem", localName(unwrapLiteral(toStr(p.get("GLitem")))));
    out.put("gltag", localName(unwrapLiteral(toStr(p.get("gltag")))));
    return out;
  }

  private static String normalizeNumber(String value) {
    String v = unwrapLiteral(value);
    if (v.isEmpty()) return "0.0";
    try {
      return Double.toString(Double.parseDouble(v));
    } catch (Exception e) {
      return "0.0";
    }
  }

  private static String normalizeDependList(String value) {
    String normalized = unwrapLiteral(value);
    if (normalized.isEmpty() || "[]".equals(normalized)) return "[\"\"]";

    Matcher matcher = Pattern.compile("\\\"([^\\\"]*)\\\"").matcher(normalized);
    List<String> items = new ArrayList<>();
    while (matcher.find()) {
      String item = localName(unwrapLiteral(matcher.group(1)));
      if (!item.isEmpty()) items.add(item);
    }

    if (items.isEmpty()) {
      String candidate = normalized;
      if (candidate.startsWith("[") && candidate.endsWith("]") && candidate.length() >= 2) {
        candidate = candidate.substring(1, candidate.length() - 1).trim();
      }
      if (candidate.startsWith("\"") && candidate.endsWith("\"") && candidate.length() >= 2) {
        candidate = candidate.substring(1, candidate.length() - 1);
      }
      candidate = localName(unwrapLiteral(candidate));
      if (!candidate.isEmpty()) items.add(candidate);
    }

    if (items.isEmpty()) return "[\"\"]";

    Collections.sort(items);
    StringBuilder sb = new StringBuilder("[");
    for (int i = 0; i < items.size(); i++) {
      if (i > 0) sb.append(",");
      sb.append("\"").append(items.get(i)).append("\"");
    }
    sb.append("]");
    return sb.toString();
  }

  private static String unwrapLiteral(String value) {
    if (value == null) return "";
    String out = value.trim();
    if (out.isEmpty()) return out;

    int dtype = out.indexOf("^^");
    if (dtype > 0) out = out.substring(0, dtype);

    if (out.length() >= 2 && out.startsWith("\"") && out.endsWith("\"")) {
      out = out.substring(1, out.length() - 1);
    }

    out = out.replace("\\\"", "\"");
    out = out.replace("\\\\", "\\");
    return out.trim();
  }

  /** Returns the local name (substring after the last '/') of a URI. */
  private static String localName(String uri) {
    if (uri == null) return "";
    int idx = uri.lastIndexOf('/');
    return idx >= 0 ? uri.substring(idx + 1) : uri;
  }

  private static String toStr(Object obj) {
    return obj != null ? obj.toString() : null;
  }

  private static Map<String, Object> buildOverlaySummary(
      Map<String, Map<String, Map<String, String>>> overlayValues) {
    Map<String, Object> summary = new LinkedHashMap<>();
    int nodesWithOverlay = overlayValues != null ? overlayValues.size() : 0;
    int totalPhaseEntries = 0;

    if (overlayValues != null) {
      for (Map<String, Map<String, String>> phaseMap : overlayValues.values()) {
        if (phaseMap != null) {
          totalPhaseEntries += phaseMap.size();
        }
      }
    }

    summary.put("nodesWithOverlay", nodesWithOverlay);
    summary.put("totalPhaseEntries", totalPhaseEntries);
    return summary;
  }
}
