package reactors.compositionTimeline;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import prerna.engine.api.IConstructStatement;
import prerna.engine.api.IConstructWrapper;
import prerna.engine.api.IDatabaseEngine;
import prerna.rdf.engine.wrappers.WrapperManager;
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
  private static final String CONCEPT_SYSTEM =
      "<http://semoss.org/ontologies/Concept/System>";
  private static final String CONCEPT_SYSTEM_INTERFACE =
      "<http://semoss.org/ontologies/Concept/SystemInterface>";
  private static final String CONCEPT_DATA_OBJECT =
      "<http://semoss.org/ontologies/Concept/DataObject>";
  private static final String CONCEPT_ICD =
      "<http://semoss.org/ontologies/Concept/SystemInterface>";
  private static final String REL_PROVIDE =
      "<http://semoss.org/ontologies/Relation/Provide>";
  private static final String REL_CONSUME =
      "<http://semoss.org/ontologies/Relation/Consume>";
  private static final String REL_PAYLOAD =
      "<http://semoss.org/ontologies/Relation/Payload>";

  // URI prefixes for STRSTARTS-based predicate matching.
  // SEMOSS names all sub-property instances with the parent relation URI as a prefix
  // (e.g., Relation/Provide/System1:ICD1 is a sub-property of Relation/Provide).
  // Using STRSTARTS avoids relying on subPropertyOf schema triples being present in
  // the SELECT query index, which the IRawSelectWrapper does not guarantee.
  private static final String REL_PROVIDE_PFX  = "http://semoss.org/ontologies/Relation/Provide";
  private static final String REL_CONSUME_PFX  = "http://semoss.org/ontologies/Relation/Consume";
  private static final String REL_PAYLOAD_PFX  = "http://semoss.org/ontologies/Relation/Payload";
  private static final String REL_CONTAINS_PFX = "http://semoss.org/ontologies/Relation/Contains/";
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

    // Execute the same CONSTRUCT used by legacy insight #21 against each engine.
    // The CONSTRUCT resolves subPropertyOf through the engine's OWL schema,
    // which IRawSelectWrapper does not support for variable predicate binding.
    // TAP_Core_Data uses ActiveSystem (declared in its OWL); FutureDB uses System
    // (ActiveSystem triples are lost during loader-sheet round-trips since FutureDB's
    // OWL does not declare ActiveSystem).
    buildBaseGraphFromEngine(new QueryExecutor(baseEngineId), systemUri, CONCEPT_ACTIVE_SYSTEM, nodes, edges);
    buildBaseGraphFromEngine(new QueryExecutor(decommEngineId), systemUri, CONCEPT_SYSTEM, nodes, edges);

    // The CONSTRUCT's Payload branch requires a Contains join on the Payload relation
    // instance, which may not exist for all ICD→DataObject connections. The legacy
    // genBaseGraph sidesteps this by running a broad SELECT on the in-memory model.
    // Load any missing Payload edges via a direct SELECT as a fallback.
    loadPayloadEdges(new QueryExecutor(baseEngineId), systemUri, nodes, edges);
    loadPayloadEdges(new QueryExecutor(decommEngineId), systemUri, nodes, edges);

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
   * Executes the legacy insight #21 CONSTRUCT query against one engine and
   * classifies the materialized triples into nodes and edges.
   *
   * <p>This faithfully replicates the legacy {@code GraphTimePlaySheet} flow:
   * the engine's OWL schema resolves {@code subPropertyOf} bindings inside the
   * CONSTRUCT, and the resulting triples are classified by predicate URI prefix
   * (Provide → system-to-ICD edge, Consume → ICD-to-system edge, etc.).
   */
  private void buildBaseGraphFromEngine(QueryExecutor executor, String systemUri,
      String systemTypeConcept, Map<String, Map<String, Object>> nodes, List<Map<String, Object>> edges) {

    IDatabaseEngine engine = executor.getEngine();
    String constructQuery = buildBaseConstructQuery(systemUri, systemTypeConcept);

    LOGGER.info("GetCompositionTimeline: executing CONSTRUCT on engine={}", executor.getEngineId());

    IConstructWrapper cWrapper = null;
    try {
      cWrapper = WrapperManager.getInstance().getCWrapper(engine, constructQuery);
    } catch (Exception e) {
      LOGGER.error("GetCompositionTimeline: CONSTRUCT failed on engine={}: {}",
          executor.getEngineId(), e.getMessage(), e);
      return;
    }

    if (cWrapper == null) {
      LOGGER.warn("GetCompositionTimeline: getCWrapper returned null for engine={}",
          executor.getEngineId());
      return;
    }

    int tripleCount = 0;
    int provideCount = 0, consumeCount = 0, payloadCount = 0, containsCount = 0, otherCount = 0;
    List<String> sampleUnclassified = new ArrayList<>();

    while (cWrapper.hasNext()) {
      IConstructStatement stmt = cWrapper.next();
      String s = stmt.getSubject();
      String p = stmt.getPredicate();
      Object o = stmt.getObject();
      String oStr = (o != null) ? o.toString() : null;

      if (s == null || p == null || oStr == null) continue;
      tripleCount++;

      if (p.startsWith(REL_PROVIDE_PFX)) {
        // subject = system, object = ICD
        addSystemNode(nodes, s);
        addICDNode(nodes, oStr);
        addEdgeIfAbsent(edges, s, oStr, "Provide");
        provideCount++;

      } else if (p.startsWith(REL_CONSUME_PFX)) {
        // subject = ICD, object = system
        addICDNode(nodes, s);
        addSystemNode(nodes, oStr);
        addEdgeIfAbsent(edges, s, oStr, "Consume");
        consumeCount++;

      } else if (p.startsWith(REL_PAYLOAD_PFX)) {
        // subject = ICD, object = DataObject
        addICDNode(nodes, s);
        addDataObjectNode(nodes, oStr);
        addEdgeIfAbsent(edges, s, oStr, "Payload");
        payloadCount++;

      } else if (p.startsWith(REL_CONTAINS_PFX)) {
        // Contains property projected onto the ICD by the CONSTRUCT template
        addICDNode(nodes, s);
        @SuppressWarnings("unchecked")
        Map<String, Object> propHash = (Map<String, Object>) nodes.get(s).get("propHash");
        propHash.put(localName(p), oStr);
        containsCount++;

      } else {
        otherCount++;
        if (sampleUnclassified.size() < 5) {
          sampleUnclassified.add("S=" + localName(s) + " P=" + p + " O=" + localName(oStr));
        }
      }
    }

    LOGGER.info("GetCompositionTimeline: CONSTRUCT on engine={} yielded {} triples "
        + "(Provide={} Consume={} Payload={} Contains={} Other={}) → {} nodes, {} edges",
        executor.getEngineId(), tripleCount,
        provideCount, consumeCount, payloadCount, containsCount, otherCount,
        nodes.size(), edges.size());
    if (!sampleUnclassified.isEmpty()) {
      LOGGER.warn("GetCompositionTimeline: unclassified triples: {}", sampleUnclassified);
    }
  }

  /**
   * Builds the CONSTRUCT query from legacy insight #21 (FutureDB_Questions.XML,
   * Time-Perspective T1), with the system URI and type concept parameterized.
   *
   * <p>The {@code systemTypeConcept} allows using {@code ActiveSystem} for engines
   * that declare it (TAP_Core_Data) and {@code System} for engines that don't
   * (FutureDB — whose OWL never declared ActiveSystem, so loader-sheet rebuilds
   * silently drop those type triples).
   *
   * <p>Two UNION branches:
   * <ul>
   *   <li>Branch 1: focal system is provider (System1 → ICD → System2)</li>
   *   <li>Branch 2: focal system is consumer (System3 → ICD2 → System1)</li>
   * </ul>
   * Both branches also pull DataObject via Payload and Contains properties.
   */
  private String buildBaseConstructQuery(String systemUri, String systemTypeConcept) {
    return "CONSTRUCT {"
        + " ?System1 ?Upstream ?ICD ."
        + " ?ICD ?Downstream ?System2 ."
        + " ?ICD ?carries ?Data1 ."
        + " ?ICD ?contains2 ?prop2 ."
        + " ?System3 ?Upstream2 ?ICD2 ."
        + " ?ICD2 ?contains1 ?prop ."
        + " ?ICD2 ?Downstream2 ?System1 ."
        + " ?ICD2 ?carries2 ?Data2"
        + " } WHERE {"
        + " {?System1 " + RDF_TYPE + " " + systemTypeConcept + " ;}"
        + " BIND(<" + systemUri + "> AS ?System1)"
        + " {"
        + "  {?System2 " + RDF_TYPE + " " + systemTypeConcept + " ;}"
        + "  {?Upstream " + RDFS_SUB_PROPERTY_OF + " " + REL_PROVIDE + " ;}"
        + "  {?ICD " + RDF_TYPE + " " + CONCEPT_ICD + " ;}"
        + "  {?Downstream " + RDFS_SUB_PROPERTY_OF + " " + REL_CONSUME + " ;}"
        + "  {?Data1 " + RDF_TYPE + " " + CONCEPT_DATA_OBJECT + " ;}"
        + "  {?carries " + RDFS_SUB_PROPERTY_OF + " " + REL_PAYLOAD + " ;}"
        + "  {?System1 ?Upstream ?ICD ;}"
        + "  {?ICD ?Downstream ?System2 ;}"
        + "  {?ICD ?carries ?Data1 ;}"
        + "  {?carries ?contains2 ?prop2}"
        + "  {?contains2 " + RDF_TYPE + " " + REL_CONTAINS + " ;}"
        + " } UNION {"
        + "  {?Upstream2 " + RDFS_SUB_PROPERTY_OF + " " + REL_PROVIDE + " ;}"
        + "  {?Downstream2 " + RDFS_SUB_PROPERTY_OF + " " + REL_CONSUME + " ;}"
        + "  {?System3 " + RDF_TYPE + " " + systemTypeConcept + " ;}"
        + "  {?ICD2 " + RDF_TYPE + " " + CONCEPT_ICD + " ;}"
        + "  {?Data2 " + RDF_TYPE + " " + CONCEPT_DATA_OBJECT + " ;}"
        + "  {?carries2 " + RDFS_SUB_PROPERTY_OF + " " + REL_PAYLOAD + " ;}"
        + "  {?System3 ?Upstream2 ?ICD2 ;}"
        + "  {?ICD2 ?Downstream2 ?System1 ;}"
        + "  {?ICD2 ?carries2 ?Data2 ;}"
        + "  {?carries2 ?contains1 ?prop}"
        + "  {?contains1 " + RDF_TYPE + " " + REL_CONTAINS + " ;}"
        + " }"
        + "}";
  }

  /**
   * Loads RDF Contains property values for the given ICD URIs into their propHash entries.
   * Retained for potential future use; the CONSTRUCT-based base graph already loads
   * ICD properties from the Payload sub-property Contains chain.
   */
  private void loadContainsProperties(QueryExecutor executor,
      Map<String, Map<String, Object>> nodes, List<String> icdUris) {

    // Contains data-properties follow the Relation/Contains/PropertyName URI pattern.
    // Use STRSTARTS with trailing slash to match only property predicates, not the
    // Contains class URI itself.
    String containsQuery =
        "SELECT DISTINCT ?node ?prop ?val WHERE {"
        + " ?node ?predicate ?val ."
        + " FILTER(STRSTARTS(STR(?predicate), '" + REL_CONTAINS_PFX + "'))"
        + " BIND(?predicate AS ?prop)"
        + " VALUES ?node {" + buildValues(icdUris) + "}"
        + "}";

    List<Map<String, String>> rows = safeExecute(executor, containsQuery);
    // String loggingQuery = containsQuery + " ORDER BY ?futureICD ?phase ?GLitem";  
    // List<Map<String, String>> rows = safeExecute(executor, loggingQuery);
    
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

  /**
   * Loads ICD→DataObject (Payload) edges via a direct SELECT query.
   *
   * <p>The CONSTRUCT query's Payload branch requires a Contains join on the
   * Payload relation instance ({@code ?carries ?contains2 ?prop2} where
   * {@code ?contains2 rdf:type Contains}). If a Payload relation has no Contains
   * properties, the CONSTRUCT will not produce the Payload triple, even though
   * the ICD→DataObject connection exists.
   *
   * <p>The legacy avoids this because {@code genBaseGraph} runs a separate SELECT
   * on the in-memory model that finds ALL {@code ?Subject ?Predicate ?Object}
   * where Predicate is subPropertyOf Relation — without the Contains constraint.
   *
   * <p>This method replicates that behavior: for every ICD already in the graph,
   * find its DataObject connections via Payload sub-properties and add them.
   */
  private void loadPayloadEdges(QueryExecutor executor, String systemUri,
      Map<String, Map<String, Object>> nodes, List<Map<String, Object>> edges) {

    // Collect ICD URIs already in the graph
    List<String> icdUris = new ArrayList<>();
    for (Map.Entry<String, Map<String, Object>> entry : nodes.entrySet()) {
      @SuppressWarnings("unchecked")
      Map<String, Object> propHash = (Map<String, Object>) entry.getValue().get("propHash");
      if (propHash != null && "SystemInterface".equals(propHash.get("VERTEX_TYPE_PROPERTY"))) {
        icdUris.add(entry.getKey());
      }
    }

    if (icdUris.isEmpty()) return;

    String query =
        "SELECT DISTINCT ?ICD ?Data WHERE {"
        + " {?carries " + RDFS_SUB_PROPERTY_OF + " " + REL_PAYLOAD + " ;}"
        + " {?ICD ?carries ?Data ;}"
        + " {?Data " + RDF_TYPE + " " + CONCEPT_DATA_OBJECT + " ;}"
        + " VALUES ?ICD {" + buildValues(icdUris) + "}"
        + "}";

    List<Map<String, String>> rows = safeExecute(executor, query);
    int added = 0;
    for (Map<String, String> row : rows) {
      String icdUri = row.get("ICD");
      String dataUri = row.get("Data");
      if (icdUri == null || dataUri == null) continue;

      addDataObjectNode(nodes, dataUri);
      int edgesBefore = edges.size();
      addEdgeIfAbsent(edges, icdUri, dataUri, "Payload");
      if (edges.size() > edgesBefore) added++;
    }

    LOGGER.info("GetCompositionTimeline: Payload fallback on engine={} found {} rows, added {} new edges",
        executor.getEngineId(), rows.size(), added);
  }

  // ──────────────────────────────────────────────────────────────────────────
  // TIME OVERLAYS  (matches GraphTimePlaySheet.processTimeData behavior)

  /**
   * Q1 — FutureCostDB: TransitionGLItem LOE phase overlay.
   * Attaches {phase, LOE, dependICDS, GLitem, gltag} timeHash entries to future ICDs.
   * Uses Concept/InterfaceControlDocument (FutureCostDB's ontology term for ICDs).
   */
  private void applyLOEOverlay(QueryExecutor executor,
      Map<String, Map<String, Object>> nodes) {
    String query =
        "SELECT DISTINCT ?futureICD ?phase"
        + " (CONCAT('[\"',GROUP_CONCAT(DISTINCT ?oldICD ; SEPARATOR = '\",\"'),'\"]') AS ?dependICDS)"
        + " ?GLitem (ROUND(?loe) AS ?LOE) ?gltag"
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
        + " } GROUP BY ?phase ?futureICD ?GLitem ?loe ?gltag";

    List<Map<String, String>> rows = safeExecute(executor, query);
    for (Map<String, String> row : rows) {
      String icdUri = row.get("futureICD");
      String phase = row.get("phase");
      if (icdUri == null || phase == null) continue;

      // The legacy uses getSWrapper which returns display names (localName).
      // Our SELECT returns full URIs, so strip the URI prefix to match.
      String phaseKey = localName(unwrapLiteral(phase));

      Map<String, Object> nodeEntry = nodes.get(icdUri);
      if (nodeEntry == null) continue;

      Map<String, Object> phaseData = new HashMap<>();
      phaseData.put("phase", phaseKey);
      phaseData.put("LOE", parseDouble(row.get("LOE")));
      phaseData.put("dependICDS", localNameList(row.getOrDefault("dependICDS", "[\"\"]")));
      phaseData.put("GLitem", localName(unwrapLiteral(row.getOrDefault("GLitem", ""))));
      phaseData.put("gltag", localName(unwrapLiteral(row.getOrDefault("gltag", ""))));

      mergeTimeHash(nodeEntry, phaseKey, phaseData);
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
        + " {?decoICD " + RDF_TYPE + " " + CONCEPT_ICD + " ;}"
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

    // Last-write-wins — matches legacy GraphTimePlaySheet.storeTimeHash() which uses
    // Hashtable.putAll().
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

  /**
   * Converts a JSON array string of URIs (produced by GROUP_CONCAT) into a JSON array
   * of local names. e.g. {@code ["http://.../ICD1","http://.../ICD2"]} → {@code ["ICD1","ICD2"]}.
   * The legacy getSWrapper returns display names, so we match that.
   */
  private static String localNameList(String jsonArray) {
    String normalized = unwrapLiteral(jsonArray);
    if (normalized == null || normalized.isEmpty()) return "[\"\"]";

    // Legacy often stores empty dependency as [""]
    if ("[]".equals(normalized)) return "[\"\"]";

    // Extract quoted entries robustly (handles escaped quotes from typed literals).
    Matcher matcher = Pattern.compile("\\\"([^\\\"]*)\\\"").matcher(normalized);
    List<String> parts = new ArrayList<>();
    while (matcher.find()) {
      parts.add(matcher.group(1));
    }

    // Fallback for non-JSON single-value strings.
    if (parts.isEmpty()) {
      String candidate = normalized;
      if (candidate.startsWith("[") && candidate.endsWith("]") && candidate.length() >= 2) {
        candidate = candidate.substring(1, candidate.length() - 1).trim();
      }
      if (candidate.startsWith("\"") && candidate.endsWith("\"") && candidate.length() >= 2) {
        candidate = candidate.substring(1, candidate.length() - 1);
      }
      if (!candidate.isEmpty()) {
        parts.add(candidate);
      }
    }

    if (parts.isEmpty()) return "[\"\"]";

    StringBuilder sb = new StringBuilder("[");
    for (int i = 0; i < parts.size(); i++) {
      String part = unwrapLiteral(parts.get(i));
      if (i > 0) sb.append(",");
      sb.append("\"").append(localName(part)).append("\"");
    }
    sb.append("]");
    return sb.toString();
  }

  private static double parseDouble(String val) {
    String normalized = unwrapLiteral(val);
    if (normalized == null || normalized.isEmpty()) return 0.0;
    try { return Double.parseDouble(normalized); }
    catch (NumberFormatException e) { return 0.0; }
  }

  /**
   * Normalizes wrapper literal representations into lexical values.
   * Examples:
   *   "12.0"^^<...#double> -> 12.0
   *   "[\"uri1\",\"uri2\"]" -> ["uri1","uri2"]
   *   plain URIs remain unchanged.
   */
  private static String unwrapLiteral(String value) {
    if (value == null) return "";

    String out = value.trim();
    if (out.isEmpty()) return out;

    // Drop datatype and language suffixes on literals.
    int dtype = out.indexOf("^^");
    if (dtype > 0) out = out.substring(0, dtype);
    int lang = out.lastIndexOf('@');
    if (lang > 0 && out.startsWith("\"") && out.lastIndexOf('"') < lang) {
      out = out.substring(0, lang);
    }

    // Remove one layer of wrapping quotes when present.
    if (out.length() >= 2 && out.startsWith("\"") && out.endsWith("\"")) {
      out = out.substring(1, out.length() - 1);
    }

    // Unescape common JSON string escaping from wrapper values.
    out = out.replace("\\\"", "\"");
    out = out.replace("\\\\", "\\");

    return out.trim();
  }

  @Override
  public String getReactorDescription() {
    return "Returns the composition-over-time graph for a selected system (insight #21).";
  }
}
