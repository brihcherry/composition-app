package reactors.compositionTimeline;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import prerna.sablecc2.om.PixelDataType;
import prerna.sablecc2.om.ReactorKeysEnum;
import prerna.sablecc2.om.nounmeta.NounMetadata;
import reactors.AbstractProjectReactor;
import util.QueryExecutor;

/**
 * Replicates legacy insight #21 "How does the composition of this system change over time?"
 *
 * <p>Mirrors {@code GraphTimePlaySheet} + {@code GraphDataModel} behavior:
 * <ol>
 *   <li>Base graph: queries TAP_Core_Data AND FutureDB for the selected system's ICDs,
 *       connected systems, and DataObjects (same as CONSTRUCT on both engines).</li>
 *   <li>Q1 (FutureCostDB): TransitionGLItem LOE phase overlay → attaches timeHash to future ICDs.</li>
 *   <li>Q2 (FutureDB): ProposedDecommissioned ICD overlay → marks legacy ICDs as Decommissioned.</li>
 *   <li>Q3 (TAP_Core_Data): BoS high-probability systems → marks them as Decommissioned.</li>
 *   <li>Q4 (TAP_Core_Data): ICDs connected to BoS systems → marks them as Decommissioned.</li>
 * </ol>
 *
 * <p>Pixel call:
 * <pre>
 *   GetCompositionTimeline(
 *     systemUri=["http://health.mil/ontologies/Concept/ActiveSystem/MHS_GENESIS"]
 *   );
 * </pre>
 *
 * <p>Optional overrides (fall back to project.properties):
 * <pre>
 *   GetCompositionTimeline(
 *     systemUri=["..."],
 *     database=["133db94b-..."],
 *     decommissionDatabase=["df69df03-..."],
 *     transitionDatabase=["6897e1e5-..."]
 *   );
 * </pre>
 */
public class GetCompositionTimelineReactor extends AbstractProjectReactor {

  private static final Logger LOGGER = LogManager.getLogger(GetCompositionTimelineReactor.class);

  private static final String SYSTEM_URI_KEY = "systemUri";
  private static final String DATABASE_KEY = ReactorKeysEnum.DATABASE.getKey();
  private static final String DECOMMISSION_DB_KEY = "decommissionDatabase";
  private static final String TRANSITION_DB_KEY = "transitionDatabase";

  // Node color constants matching legacy GraphDataModel defaults
  private static final String SYSTEM_COLOR = "31,119,180";
  private static final String ICD_COLOR = "44,160,44";
  private static final String DATA_OBJECT_COLOR = "255,127,14";

  // SPARQL namespaces
  private static final String RDF_TYPE =
      "<http://www.w3.org/1999/02/22-rdf-syntax-ns#type>";
  private static final String RDFS_SUB_PROPERTY_OF =
      "<http://www.w3.org/2000/01/rdf-schema#subPropertyOf>";
  private static final String CONCEPT_ACTIVE_SYSTEM =
      "<http://semoss.org/ontologies/Concept/ActiveSystem>";
  private static final String CONCEPT_SYSTEM_INTERFACE =
      "<http://semoss.org/ontologies/Concept/SystemInterface>";
  private static final String CONCEPT_DATA_OBJECT =
      "<http://semoss.org/ontologies/Concept/DataObject>";
  private static final String CONCEPT_ICD =
      "<http://semoss.org/ontologies/Concept/InterfaceControlDocument>";
  private static final String REL_PROVIDE =
      "<http://semoss.org/ontologies/Relation/Provide>";
  private static final String REL_CONSUME =
      "<http://semoss.org/ontologies/Relation/Consume>";
  private static final String REL_PAYLOAD =
      "<http://semoss.org/ontologies/Relation/Payload>";
  private static final String REL_CONTAINS =
      "<http://semoss.org/ontologies/Relation/Contains>";

  public GetCompositionTimelineReactor() {
    this.keysToGet = new String[]{SYSTEM_URI_KEY, DATABASE_KEY, DECOMMISSION_DB_KEY, TRANSITION_DB_KEY};
    this.keyRequired = new int[]{1, 0, 0, 0};
  }

  @Override
  protected NounMetadata doExecute() {
    String systemUri = this.keyValue.get(SYSTEM_URI_KEY);
    if (systemUri == null || systemUri.trim().isEmpty()) {
      throw new IllegalArgumentException("systemUri parameter is required");
    }

    // Resolve engine IDs — prefer explicit params, fall back to project.properties
    String baseEngineId = resolveEngineId(DATABASE_KEY, projectProperties.getBaseEngineId());
    String decommEngineId = resolveEngineId(DECOMMISSION_DB_KEY, projectProperties.getDecommissionEngineId());
    String transEngineId = resolveEngineId(TRANSITION_DB_KEY, projectProperties.getTransitionEngineId());

    LOGGER.info("GetCompositionTimeline: systemUri={}", systemUri);
    LOGGER.info("GetCompositionTimeline: baseEngine={}, decommEngine={}, transEngine={}",
        baseEngineId, decommEngineId, transEngineId);

    // ── 1. Build base graph ──────────────────────────────────────────────────
    // nodes: uri → propHash map; edges: list of edge maps
    Map<String, Map<String, Object>> nodes = new LinkedHashMap<>();
    List<Map<String, Object>> edges = new ArrayList<>();

    // Ensure the focal system is always in the node map
    addSystemNode(nodes, systemUri);

    // Query TAP_Core_Data (uses Concept/SystemInterface)
    buildBaseGraphFromEngine(new QueryExecutor(baseEngineId), systemUri, CONCEPT_SYSTEM_INTERFACE, nodes, edges);

    // Query FutureDB (same ontology namespace, uses same concept in practice)
    // FutureDB holds future/planned ICDs not yet in TAP_Core_Data
    buildBaseGraphFromEngine(new QueryExecutor(decommEngineId), systemUri, CONCEPT_SYSTEM_INTERFACE, nodes, edges);

    LOGGER.info("GetCompositionTimeline: base graph built — {} nodes, {} edges", nodes.size(), edges.size());

    // ── 2. Apply time overlays ───────────────────────────────────────────────
    applyLOEOverlay(new QueryExecutor(transEngineId), nodes);
    applyDecommissionedICDOverlay(new QueryExecutor(decommEngineId), nodes);
    applyBoSSystemOverlay(new QueryExecutor(baseEngineId), nodes);
    applyBoSICDOverlay(new QueryExecutor(baseEngineId), nodes);

    LOGGER.info("GetCompositionTimeline: overlays applied");

    // ── 3. Build response ────────────────────────────────────────────────────
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("nodes", nodes);
    result.put("edges", edges);
    result.put("layout", "prerna.ui.components.specific.tap.GraphTimePlaySheet");
    result.put("title", "How does the composition of this system change over time?");
    result.put("dataMakerName", "GraphTimePlaySheet");

    return new NounMetadata(result, PixelDataType.CUSTOM_DATA_STRUCTURE);
  }

  // ──────────────────────────────────────────────────────────────────────────
  // BASE GRAPH
  // ──────────────────────────────────────────────────────────────────────────

  /**
   * Queries one engine for ICDs where the focal system is either provider or consumer,
   * then loads their connected systems, DataObjects, and Contains properties.
   * Merges all results into the shared nodes + edges maps (deduplicates by URI).
   */
  private void buildBaseGraphFromEngine(QueryExecutor executor, String systemUri,
      String icdTypeConcept, Map<String, Map<String, Object>> nodes,
      List<Map<String, Object>> edges) {

    // ── 1a. ICDs where focal system is provider → ICD → other system ────────
    String providerQuery =
        "SELECT DISTINCT ?ICD ?consumer WHERE {"
        + " ?ICD " + RDF_TYPE + " " + icdTypeConcept + " ."
        + " ?consumer " + RDF_TYPE + " " + CONCEPT_ACTIVE_SYSTEM + " ."
        + " ?upstream " + RDFS_SUB_PROPERTY_OF + " " + REL_PROVIDE + " ."
        + " ?downstream " + RDFS_SUB_PROPERTY_OF + " " + REL_CONSUME + " ."
        + " <" + systemUri + "> ?upstream ?ICD ."
        + " ?ICD ?downstream ?consumer ."
        + "}";

    List<Map<String, String>> providerRows = safeExecute(executor, providerQuery);
    for (Map<String, String> row : providerRows) {
      String icdUri = row.get("ICD");
      String consumerUri = row.get("consumer");
      if (icdUri == null || consumerUri == null) continue;

      addICDNode(nodes, icdUri);
      addSystemNode(nodes, consumerUri);

      // System → ICD edge (Provide)
      addEdgeIfAbsent(edges, systemUri, icdUri, "Provide");
      // ICD → consumer edge (Consume)
      addEdgeIfAbsent(edges, icdUri, consumerUri, "Consume");
    }

    // ── 1b. ICDs where focal system is consumer ← other system → ICD ────────
    String consumerQuery =
        "SELECT DISTINCT ?ICD ?provider WHERE {"
        + " ?ICD " + RDF_TYPE + " " + icdTypeConcept + " ."
        + " ?provider " + RDF_TYPE + " " + CONCEPT_ACTIVE_SYSTEM + " ."
        + " ?upstream " + RDFS_SUB_PROPERTY_OF + " " + REL_PROVIDE + " ."
        + " ?downstream " + RDFS_SUB_PROPERTY_OF + " " + REL_CONSUME + " ."
        + " ?provider ?upstream ?ICD ."
        + " ?ICD ?downstream <" + systemUri + "> ."
        + "}";

    List<Map<String, String>> consumerRows = safeExecute(executor, consumerQuery);
    for (Map<String, String> row : consumerRows) {
      String icdUri = row.get("ICD");
      String providerUri = row.get("provider");
      if (icdUri == null || providerUri == null) continue;

      addICDNode(nodes, icdUri);
      addSystemNode(nodes, providerUri);

      addEdgeIfAbsent(edges, providerUri, icdUri, "Provide");
      addEdgeIfAbsent(edges, icdUri, systemUri, "Consume");
    }

    // Collect ICD URIs found in both passes for DataObject + Contains queries
    List<String> icdUris = new ArrayList<>();
    for (Map.Entry<String, Map<String, Object>> entry : nodes.entrySet()) {
      @SuppressWarnings("unchecked")
      Map<String, Object> propHash = (Map<String, Object>) entry.getValue().get("propHash");
      if ("SystemInterface".equals(propHash.get("VERTEX_TYPE_PROPERTY"))) {
        icdUris.add(entry.getKey());
      }
    }

    if (icdUris.isEmpty()) return;

    // ── 1c. DataObjects linked to those ICDs via Payload ─────────────────────
    String dataQuery =
        "SELECT DISTINCT ?ICD ?Data WHERE {"
        + " ?ICD " + RDF_TYPE + " " + icdTypeConcept + " ."
        + " ?Data " + RDF_TYPE + " " + CONCEPT_DATA_OBJECT + " ."
        + " ?carries " + RDFS_SUB_PROPERTY_OF + " " + REL_PAYLOAD + " ."
        + " ?ICD ?carries ?Data ."
        + " VALUES ?ICD {" + buildValues(icdUris) + "}"
        + "}";

    List<Map<String, String>> dataRows = safeExecute(executor, dataQuery);
    for (Map<String, String> row : dataRows) {
      String icdUri = row.get("ICD");
      String dataUri = row.get("Data");
      if (icdUri == null || dataUri == null) continue;

      addDataObjectNode(nodes, dataUri);
      addEdgeIfAbsent(edges, icdUri, dataUri, "Payload");
    }

    // ── 1d. Load Contains properties for all nodes from this engine ──────────
    loadContainsProperties(executor, nodes, icdUris);
  }

  /**
   * Loads RDF Contains property values for the given ICD URIs into their propHash entries.
   * Also loads properties for System nodes encountered so far.
   */
  private void loadContainsProperties(QueryExecutor executor,
      Map<String, Map<String, Object>> nodes, List<String> icdUris) {

    String containsQuery =
        "SELECT DISTINCT ?node ?prop ?val WHERE {"
        + " ?node ?predicate ?val ."
        + " ?predicate " + RDF_TYPE + " " + REL_CONTAINS + " ."
        + " BIND(?predicate AS ?prop)"
        + " VALUES ?node {" + buildValues(icdUris) + "}"
        + "}";

    List<Map<String, String>> rows = safeExecute(executor, containsQuery);
    for (Map<String, String> row : rows) {
      String nodeUri = row.get("node");
      String propUri = row.get("prop");
      String val = row.get("val");
      if (nodeUri == null || propUri == null || val == null) continue;

      Map<String, Map<String, Object>> nodesMap = nodes;
      Map<String, Object> nodeEntry = nodesMap.get(nodeUri);
      if (nodeEntry == null) continue;

      @SuppressWarnings("unchecked")
      Map<String, Object> propHash = (Map<String, Object>) nodeEntry.get("propHash");
      String propName = localName(propUri);
      propHash.put(propName, val);
    }
  }

  // ──────────────────────────────────────────────────────────────────────────
  // TIME OVERLAYS  (matches GraphTimePlaySheet.processTimeData behavior)
  // Column 0 = node/edge URI, Column 1 = phase key, rest = phase properties
  // ──────────────────────────────────────────────────────────────────────────

  /**
   * Q1 — FutureCostDB: TransitionGLItem LOE phase overlay.
   * Attaches {phase, LOE, dependICDS, GLitem, gltag} timeHash entries to future ICDs.
   * Uses Concept/InterfaceControlDocument (FutureCostDB's ontology term for ICDs).
   */
  private void applyLOEOverlay(QueryExecutor executor, Map<String, Map<String, Object>> nodes) {
    String query =
        "SELECT DISTINCT ?futureICD ?phase"
        + " (CONCAT('[\"',GROUP_CONCAT(DISTINCT ?oldICD ; SEPARATOR = '\",\"'),'\"]') AS ?dependICDS)"
        + " ?GLitem (ROUND(?loe) AS ?LOE) ?gltag"
        + " WHERE {"
        + " {?subclass " + RDFS_SUB_PROPERTY_OF + " " + CONCEPT_ICD + " ;}"
        + " {?subclass " + RDFS_SUB_PROPERTY_OF
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
        + " } GROUP BY ?phase ?futureICD ?GLitem ?loe ?gltag";

    List<Map<String, String>> rows = safeExecute(executor, query);
    for (Map<String, String> row : rows) {
      String icdUri = row.get("futureICD");
      String phase = row.get("phase");
      if (icdUri == null || phase == null) continue;

      Map<String, Object> nodeEntry = nodes.get(icdUri);
      if (nodeEntry == null) continue;

      Map<String, Object> phaseData = new HashMap<>();
      phaseData.put("phase", phase);
      phaseData.put("LOE", parseDouble(row.get("LOE")));
      phaseData.put("dependICDS", row.getOrDefault("dependICDS", "[\"\"]"));
      phaseData.put("GLitem", row.getOrDefault("GLitem", ""));
      phaseData.put("gltag", row.getOrDefault("gltag", ""));

      mergeTimeHash(nodeEntry, phase, phaseData);
    }

    LOGGER.info("GetCompositionTimeline: Q1 LOE overlay applied ({} rows)", rows.size());
  }

  /**
   * Q2 — FutureDB: ProposedDecommissioned ICD overlay.
   * Marks ICDs of type ProposedDecommissionedSystemInterface as Decommissioned.
   */
  private void applyDecommissionedICDOverlay(QueryExecutor executor,
      Map<String, Map<String, Object>> nodes) {
    String query =
        "SELECT DISTINCT ?decoICD ('Decommissioned' AS ?phase) WHERE {"
        + " {?decoICD " + RDF_TYPE
        +     " <http://semoss.org/ontologies/Concept/ProposedDecommissionedSystemInterface>;}"
        + "}";

    List<Map<String, String>> rows = safeExecute(executor, query);
    for (Map<String, String> row : rows) {
      String icdUri = row.get("decoICD");
      if (icdUri == null) continue;

      Map<String, Object> nodeEntry = nodes.get(icdUri);
      if (nodeEntry == null) continue;

      Map<String, Object> phaseData = new HashMap<>();
      phaseData.put("phase", "Decommissioned");
      phaseData.put("LOE", 0.0);
      phaseData.put("dependICDS", "[\"\"]");
      phaseData.put("GLitem", "");
      phaseData.put("gltag", "");

      mergeTimeHash(nodeEntry, "Decommissioned", phaseData);
    }

    LOGGER.info("GetCompositionTimeline: Q2 decommissioned ICD overlay applied ({} rows)", rows.size());
  }

  /**
   * Q3 — TAP_Core_Data: High-BoS system decommissioning overlay.
   * Marks ActiveSystem nodes with Probability_of_Included_BoS_Enterprise_EHRS = 'High' as Decommissioned.
   */
  private void applyBoSSystemOverlay(QueryExecutor executor,
      Map<String, Map<String, Object>> nodes) {
    String query =
        "SELECT DISTINCT ?System1 ('Decommissioned' AS ?phase) WHERE {"
        + " {?System1 " + RDF_TYPE + " " + CONCEPT_ACTIVE_SYSTEM + ";}"
        + " {?System1 <http://semoss.org/ontologies/Relation/Contains/Probability_of_Included_BoS_Enterprise_EHRS> 'High'}"
        + "}";

    List<Map<String, String>> rows = safeExecute(executor, query);
    for (Map<String, String> row : rows) {
      String sysUri = row.get("System1");
      if (sysUri == null) continue;

      Map<String, Object> nodeEntry = nodes.get(sysUri);
      if (nodeEntry == null) continue;

      Map<String, Object> phaseData = new HashMap<>();
      phaseData.put("phase", "Decommissioned");
      phaseData.put("LOE", 0.0);
      phaseData.put("dependICDS", "[\"\"]");
      phaseData.put("GLitem", "");
      phaseData.put("gltag", "");

      mergeTimeHash(nodeEntry, "Decommissioned", phaseData);
    }

    LOGGER.info("GetCompositionTimeline: Q3 BoS system overlay applied ({} rows)", rows.size());
  }

  /**
   * Q4 — TAP_Core_Data: ICDs connected to high-BoS systems overlay.
   * Marks ICDs that Provide to or Consume from a high-BoS system as Decommissioned.
   */
  private void applyBoSICDOverlay(QueryExecutor executor,
      Map<String, Map<String, Object>> nodes) {
    String query =
        "SELECT DISTINCT ?decoICD ('Decommissioned' AS ?phase) WHERE {"
        + " {?System1 " + RDF_TYPE + " " + CONCEPT_ACTIVE_SYSTEM + ";}"
        + " {?System1 <http://semoss.org/ontologies/Relation/Contains/Probability_of_Included_BoS_Enterprise_EHRS> 'High'}"
        + " {?decoICD " + RDF_TYPE + " " + CONCEPT_SYSTEM_INTERFACE + " ;}"
        + " {{?System1 <http://semoss.org/ontologies/Relation/Provide> ?decoICD ;}}"
        + " UNION"
        + " {{?decoICD <http://semoss.org/ontologies/Relation/Consume> ?System1 ;}}"
        + "}";

    List<Map<String, String>> rows = safeExecute(executor, query);
    for (Map<String, String> row : rows) {
      String icdUri = row.get("decoICD");
      if (icdUri == null) continue;

      Map<String, Object> nodeEntry = nodes.get(icdUri);
      if (nodeEntry == null) continue;

      Map<String, Object> phaseData = new HashMap<>();
      phaseData.put("phase", "Decommissioned");
      phaseData.put("LOE", 0.0);
      phaseData.put("dependICDS", "[\"\"]");
      phaseData.put("GLitem", "");
      phaseData.put("gltag", "");

      mergeTimeHash(nodeEntry, "Decommissioned", phaseData);
    }

    LOGGER.info("GetCompositionTimeline: Q4 BoS ICD overlay applied ({} rows)", rows.size());
  }

  // ──────────────────────────────────────────────────────────────────────────
  // NODE BUILDERS
  // ──────────────────────────────────────────────────────────────────────────

  private void addSystemNode(Map<String, Map<String, Object>> nodes, String uri) {
    if (nodes.containsKey(uri)) return;
    String name = localName(uri);
    Map<String, Object> propHash = new HashMap<>();
    propHash.put("VERTEX_TYPE_PROPERTY", "System");
    propHash.put("VERTEX_LABEL_PROPERTY", name.replace('_', ' '));
    propHash.put("VERTEX_COLOR_PROPERTY", SYSTEM_COLOR);
    propHash.put("PhysicalName", name);
    propHash.put("URI", uri);

    Map<String, Object> node = new HashMap<>();
    node.put("uri", uri);
    node.put("propHash", propHash);
    nodes.put(uri, node);
  }

  private void addICDNode(Map<String, Map<String, Object>> nodes, String uri) {
    if (nodes.containsKey(uri)) return;
    String name = localName(uri);
    Map<String, Object> propHash = new HashMap<>();
    propHash.put("VERTEX_TYPE_PROPERTY", "SystemInterface");
    propHash.put("VERTEX_LABEL_PROPERTY", name);
    propHash.put("VERTEX_COLOR_PROPERTY", ICD_COLOR);
    propHash.put("PhysicalName", name);
    propHash.put("URI", uri);

    Map<String, Object> node = new HashMap<>();
    node.put("uri", uri);
    node.put("propHash", propHash);
    nodes.put(uri, node);
  }

  private void addDataObjectNode(Map<String, Map<String, Object>> nodes, String uri) {
    if (nodes.containsKey(uri)) return;
    String name = localName(uri);
    Map<String, Object> propHash = new HashMap<>();
    propHash.put("VERTEX_TYPE_PROPERTY", "DataObject");
    propHash.put("VERTEX_LABEL_PROPERTY", name.replace('_', ' '));
    propHash.put("VERTEX_COLOR_PROPERTY", DATA_OBJECT_COLOR);
    propHash.put("PhysicalName", name);
    propHash.put("URI", uri);

    Map<String, Object> node = new HashMap<>();
    node.put("uri", uri);
    node.put("propHash", propHash);
    nodes.put(uri, node);
  }

  // ──────────────────────────────────────────────────────────────────────────
  // EDGE BUILDER
  // ──────────────────────────────────────────────────────────────────────────

  private void addEdgeIfAbsent(List<Map<String, Object>> edges,
      String sourceUri, String targetUri, String edgeType) {
    String sourceName = localName(sourceUri);
    String targetName = localName(targetUri);
    String edgeUri = "http://health.mil/ontologies/Relation/"
        + edgeType + "/" + sourceName + ":" + targetName;

    // Deduplicate by uri
    for (Map<String, Object> existing : edges) {
      if (edgeUri.equals(existing.get("uri"))) return;
    }

    Map<String, Object> propHash = new HashMap<>();
    propHash.put("EDGE_TYPE", edgeType);
    propHash.put("EDGE_NAME", sourceName + ":" + targetName);
    propHash.put("URI", edgeUri);

    Map<String, Object> edge = new HashMap<>();
    edge.put("uri", edgeUri);
    edge.put("source", sourceUri);
    edge.put("target", targetUri);
    edge.put("propHash", propHash);
    edges.add(edge);
  }

  // ──────────────────────────────────────────────────────────────────────────
  // TIME HASH HELPERS  (mirrors GraphTimePlaySheet.storeTimeHash)
  // ──────────────────────────────────────────────────────────────────────────

  @SuppressWarnings("unchecked")
  private void mergeTimeHash(Map<String, Object> nodeEntry, String phaseKey,
      Map<String, Object> phaseData) {
    Map<String, Object> propHash = (Map<String, Object>) nodeEntry.get("propHash");
    Map<String, Object> timeHash = (Map<String, Object>) propHash.get("timeHash");
    if (timeHash == null) {
      timeHash = new LinkedHashMap<>();
      propHash.put("timeHash", timeHash);
    }
    timeHash.put(phaseKey, phaseData);
  }

  // ──────────────────────────────────────────────────────────────────────────
  // HELPERS
  // ──────────────────────────────────────────────────────────────────────────

  private String resolveEngineId(String paramKey, String fallback) {
    String val = this.keyValue.get(paramKey);
    if (val != null && !val.trim().isEmpty()) return val.trim();
    if (fallback != null && !fallback.trim().isEmpty()) return fallback.trim();
    throw new IllegalStateException("Engine ID for '" + paramKey + "' is not configured");
  }

  private List<Map<String, String>> safeExecute(QueryExecutor executor, String query) {
    try {
      return executor.executeSelect(query);
    } catch (Exception e) {
      LOGGER.warn("Query failed (skipping): {}", e.getMessage());
      return new ArrayList<>();
    }
  }

  private String buildValues(List<String> uris) {
    StringBuilder sb = new StringBuilder();
    for (String uri : uris) {
      sb.append("<").append(uri).append("> ");
    }
    return sb.toString().trim();
  }

  private static String localName(String uri) {
    if (uri == null) return "";
    int slash = uri.lastIndexOf('/');
    int hash = uri.lastIndexOf('#');
    int idx = Math.max(slash, hash);
    return idx >= 0 ? uri.substring(idx + 1) : uri;
  }

  private static double parseDouble(String val) {
    if (val == null || val.trim().isEmpty()) return 0.0;
    try { return Double.parseDouble(val.trim()); }
    catch (NumberFormatException e) { return 0.0; }
  }

  @Override
  public String getReactorDescription() {
    return "Returns the composition-over-time graph for a selected system (insight #21).";
  }
}
