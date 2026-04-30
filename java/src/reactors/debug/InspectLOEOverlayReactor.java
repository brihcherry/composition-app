package reactors.debug;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import prerna.reactor.PixelPlanner;
import prerna.sablecc2.om.GenRowStruct;
import prerna.sablecc2.om.PixelDataType;
import prerna.sablecc2.om.nounmeta.NounMetadata;
import reactors.AbstractProjectReactor;
import reactors.compositionTimeline.GetCompositionTimelineReactor;
import util.QueryExecutor;

/**
 * Debug reactor that inspects LOE overlay candidates for a selected system.
 *
 * <p>Purpose:
 * <ul>
 *   <li>Show all TransitionGLItem rows that can write into the same (futureICD, phase).</li>
 *   <li>Surface ambiguous groups where candidateCount > 1.</li>
 *   <li>Help explain phase-value mismatch drift between legacy and new flows.</li>
 * </ul>
 *
 * <p>Pixel call:
 * <pre>
 *   InspectLOEOverlay(
 *     systemUri=["http://health.mil/ontologies/Concept/System/ACS_DAL"],
 *     maxRows=[800]
 *   );
 * </pre>
 */
public class InspectLOEOverlayReactor extends AbstractProjectReactor {

  private static final Logger LOGGER = LogManager.getLogger(InspectLOEOverlayReactor.class);

  private static final String SYSTEM_URI_KEY = "systemUri";
  private static final String MAX_ROWS_KEY = "maxRows";

  private static final String RDF_TYPE =
      "<http://www.w3.org/1999/02/22-rdf-syntax-ns#type>";
  private static final String RDFS_SUB_PROPERTY_OF =
      "<http://www.w3.org/2000/01/rdf-schema#subPropertyOf>";
  private static final String CONCEPT_ICD =
      "<http://semoss.org/ontologies/Concept/SystemInterface>";

  public InspectLOEOverlayReactor() {
    this.keysToGet = new String[] { SYSTEM_URI_KEY, MAX_ROWS_KEY };
    this.keyRequired = new int[] { 1, 0 };
  }

  @Override
  @SuppressWarnings("unchecked")
  protected NounMetadata doExecute() {
    String systemUri = this.keyValue.get(SYSTEM_URI_KEY);
    if (systemUri == null || systemUri.trim().isEmpty()) {
      throw new IllegalArgumentException("systemUri parameter is required");
    }

    int maxRows = parsePositiveInt(this.keyValue.get(MAX_ROWS_KEY), 600);

    String transEngineId = projectProperties.getTransitionEngineId();
    if (transEngineId == null || transEngineId.trim().isEmpty()) {
      throw new IllegalStateException("transitionEngineId is not configured in project.properties");
    }

    // Reuse the new reactor to get the exact ICD set currently in scope for this system.
    Set<String> scopedIcdUris = loadScopedIcdUris(systemUri);

    QueryExecutor executor = new QueryExecutor(transEngineId);
    List<Map<String, String>> rows = safeExecute(executor, buildLOEQuery());

    List<Map<String, Object>> candidateRows = new ArrayList<>();
    Map<String, List<Map<String, Object>>> grouped = new LinkedHashMap<>();

    for (Map<String, String> row : rows) {
      String futureIcd = row.get("futureICD");
      String phase = localName(row.get("phase"));
      if (futureIcd == null || phase.isEmpty()) continue;
      if (!scopedIcdUris.contains(futureIcd)) continue;

      Map<String, Object> candidate = new LinkedHashMap<>();
      candidate.put("futureICD", futureIcd);
      candidate.put("futureICDLabel", localName(futureIcd));
      candidate.put("phase", phase);
      candidate.put("GLitem", localName(row.get("GLitem")));
      candidate.put("gltag", localName(row.get("gltag")));
      candidate.put("influencerSystem", localName(row.get("sys")));
      candidate.put("LOE", parseDouble(row.get("LOE")));
      candidate.put("oldICD", localName(row.get("oldICD")));

      candidateRows.add(candidate);

      String key = futureIcd + "|" + phase;
      grouped.computeIfAbsent(key, k -> new ArrayList<>()).add(candidate);
    }

    List<Map<String, Object>> ambiguousGroups = new ArrayList<>();
    for (Map.Entry<String, List<Map<String, Object>>> entry : grouped.entrySet()) {
      List<Map<String, Object>> groupRows = entry.getValue();
      if (groupRows.size() < 2) continue;

      Map<String, Object> first = groupRows.get(0);
      Set<String> glTags = new TreeSet<>();
      Set<String> influencers = new TreeSet<>();
      Set<String> glItems = new TreeSet<>();
      Set<Double> loes = new TreeSet<>();

      for (Map<String, Object> groupRow : groupRows) {
        glTags.add(str(groupRow.get("gltag")));
        influencers.add(str(groupRow.get("influencerSystem")));
        glItems.add(str(groupRow.get("GLitem")));
        loes.add(asDouble(groupRow.get("LOE")));
      }

      Map<String, Object> groupSummary = new LinkedHashMap<>();
      groupSummary.put("futureICD", first.get("futureICD"));
      groupSummary.put("futureICDLabel", first.get("futureICDLabel"));
      groupSummary.put("phase", first.get("phase"));
      groupSummary.put("candidateCount", groupRows.size());
      groupSummary.put("glTags", new ArrayList<>(glTags));
      groupSummary.put("influencerSystems", new ArrayList<>(influencers));
      groupSummary.put("glItems", new ArrayList<>(glItems));
      groupSummary.put("loeValues", new ArrayList<>(loes));
      groupSummary.put("candidates", groupRows);
      ambiguousGroups.add(groupSummary);
    }

    // Show the densest collisions first.
    Collections.sort(
        ambiguousGroups,
        (a, b) -> Integer.compare(intVal(b.get("candidateCount")), intVal(a.get("candidateCount"))));

    Map<String, Object> result = new LinkedHashMap<>();
    result.put("systemUri", systemUri);
    result.put("system", localName(systemUri));
    result.put("transitionEngineId", transEngineId);
    result.put("scopedICDCount", scopedIcdUris.size());
    result.put("rawRowCount", rows.size());
    result.put("candidateRowCount", candidateRows.size());
    result.put("ambiguousGroupCount", ambiguousGroups.size());
    result.put("groupsEvaluated", grouped.size());
    result.put("ambiguousGroups", ambiguousGroups);

    if (candidateRows.size() > maxRows) {
      result.put("rowsTruncated", true);
      result.put("rows", candidateRows.subList(0, maxRows));
    } else {
      result.put("rowsTruncated", false);
      result.put("rows", candidateRows);
    }

    LOGGER.info(
        "InspectLOEOverlay: system={} scopedICDs={} rawRows={} candidateRows={} ambiguousGroups={}",
        localName(systemUri), scopedIcdUris.size(), rows.size(), candidateRows.size(), ambiguousGroups.size());

    return new NounMetadata(result, PixelDataType.CUSTOM_DATA_STRUCTURE);
  }

  private Set<String> loadScopedIcdUris(String systemUri) {
    GetCompositionTimelineReactor reactor = new GetCompositionTimelineReactor();
    reactor.In();
    reactor.setInsight(this.insight);

    PixelPlanner planner = new PixelPlanner();
    planner.setVarStore(this.insight.getVarStore());
    reactor.setPixelPlanner(planner);

    GenRowStruct grsSystem = new GenRowStruct();
    grsSystem.add(new NounMetadata(systemUri, PixelDataType.CONST_STRING));
    reactor.getNounStore().addNoun("systemUri", grsSystem);

    NounMetadata output = reactor.execute();
    Map<String, Object> payload = (Map<String, Object>) output.getValue();

    Set<String> icdUris = new TreeSet<>();
    Object nodesObj = payload.get("nodes");
    if (!(nodesObj instanceof Map)) return icdUris;

    Map<String, Object> nodes = (Map<String, Object>) nodesObj;
    for (Map.Entry<String, Object> entry : nodes.entrySet()) {
      if (!(entry.getValue() instanceof Map)) continue;
      Map<String, Object> node = (Map<String, Object>) entry.getValue();
      Object propObj = node.get("propHash");
      if (!(propObj instanceof Map)) continue;
      Map<String, Object> propHash = (Map<String, Object>) propObj;
      if ("SystemInterface".equals(str(propHash.get("VERTEX_TYPE_PROPERTY")))) {
        icdUris.add(entry.getKey());
      }
    }

    return icdUris;
  }

  private String buildLOEQuery() {
    return "SELECT DISTINCT ?sys ?futureICD ?phase ?GLitem (ROUND(?loe) AS ?LOE) ?gltag ?oldICD"
        + " WHERE {"
        + " {?subclass <http://www.w3.org/2000/01/rdf-schema#subClassOf>"
        +     " <http://semoss.org/ontologies/Concept/TransitionGLItem> ;}"
        + " {?GLitem " + RDF_TYPE + " ?subclass ;}"
        + " {?tagged " + RDFS_SUB_PROPERTY_OF
        +     " <http://semoss.org/ontologies/Relation/TaggedBy>;}"
        + " {?gltag " + RDF_TYPE + " <http://semoss.org/ontologies/Concept/GLTag> ;}"
        + " {?GLitem ?tagged ?gltag;}"
        + " {?influences " + RDFS_SUB_PROPERTY_OF
        +     " <http://semoss.org/ontologies/Relation/Influences>;}"
        + " {?sys ?influences ?GLitem ;}"
        + " {?GLitem <http://semoss.org/ontologies/Relation/Contains/LOEcalc> ?loe;}"
        + " {?phase " + RDF_TYPE + " <http://semoss.org/ontologies/Concept/SDLCPhase> ;}"
        + " {?belongs " + RDFS_SUB_PROPERTY_OF
        +     " <http://semoss.org/ontologies/Relation/BelongsTo>;}"
        + " {?GLitem ?belongs ?phase ;}"
        + " {?futureICD " + RDF_TYPE + " " + CONCEPT_ICD + " ;}"
        + " {?output " + RDFS_SUB_PROPERTY_OF
        +     " <http://semoss.org/ontologies/Relation/Output>;}"
        + " {?GLitem ?output ?futureICD ;}"
        + " OPTIONAL {"
        +   " {?input " + RDFS_SUB_PROPERTY_OF
        +       " <http://semoss.org/ontologies/Relation/Input>;}"
        +   " {?oldICD ?input ?GLitem}"
        +   " {?oldICD " + RDF_TYPE + " " + CONCEPT_ICD + " ;}"
        + " }"
        + " }";
  }

  private List<Map<String, String>> safeExecute(QueryExecutor executor, String query) {
    try {
      return executor.executeSelect(query);
    } catch (Exception e) {
      LOGGER.warn("InspectLOEOverlay query failed: {}", e.getMessage());
      return new ArrayList<>();
    }
  }

  private int parsePositiveInt(String value, int fallback) {
    if (value == null || value.trim().isEmpty()) return fallback;
    try {
      int parsed = Integer.parseInt(value.trim());
      return parsed > 0 ? parsed : fallback;
    } catch (NumberFormatException e) {
      return fallback;
    }
  }

  private double parseDouble(String value) {
    if (value == null || value.trim().isEmpty()) return 0.0;
    try {
      return Double.parseDouble(value);
    } catch (NumberFormatException e) {
      return 0.0;
    }
  }

  private double asDouble(Object value) {
    if (value instanceof Number) {
      return ((Number) value).doubleValue();
    }
    try {
      return Double.parseDouble(str(value));
    } catch (NumberFormatException e) {
      return 0.0;
    }
  }

  private int intVal(Object value) {
    if (value instanceof Number) {
      return ((Number) value).intValue();
    }
    try {
      return Integer.parseInt(str(value));
    } catch (NumberFormatException e) {
      return 0;
    }
  }

  private String str(Object value) {
    return value == null ? "" : String.valueOf(value);
  }

  private String localName(String uri) {
    if (uri == null) return "";
    int slash = uri.lastIndexOf('/');
    int hash = uri.lastIndexOf('#');
    int idx = Math.max(slash, hash);
    return idx >= 0 ? uri.substring(idx + 1) : uri;
  }

  @Override
  public String getReactorDescription() {
    return "Debug helper: inspects LOE overlay candidate collisions per ICD/phase for one system.";
  }
}
