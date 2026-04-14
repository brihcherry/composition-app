package reactors.compositionTimeline;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import prerna.sablecc2.om.PixelDataType;
import prerna.sablecc2.om.nounmeta.NounMetadata;
import reactors.AbstractProjectReactor;
import util.QueryExecutor;

/**
 * Returns the list of ActiveSystem nodes from FutureDB for use in the system selector dropdown.
 *
 * <p>Replicates the legacy PARAM query for insight #21 (FutureDB:Time-Perspective:T1):
 * <pre>
 *   SELECT ?entity WHERE {
 *     ?entity &lt;rdf:type&gt; &lt;http://semoss.org/ontologies/Concept/ActiveSystem&gt; ;
 *   }
 * </pre>
 * which runs on the FutureDB core engine (decommissionEngineId in project.properties).
 *
 * <p>Pixel call:
 * <pre>
 *   ListActiveSystems();
 * </pre>
 *
 * <p>Returns: {@code List<Map>} where each map has {@code "uri"} and {@code "label"} keys,
 * sorted alphabetically by label. Matches the {@code SystemOption} interface on the frontend.
 */
public class ListActiveSystemsReactor extends AbstractProjectReactor {

  private static final Logger LOGGER = LogManager.getLogger(ListActiveSystemsReactor.class);

  private static final String CONCEPT_ACTIVE_SYSTEM =
      "http://semoss.org/ontologies/Concept/ActiveSystem";

  public ListActiveSystemsReactor() {
    this.keysToGet = new String[]{};
    this.keyRequired = new int[]{};
  }

  @Override
  protected NounMetadata doExecute() {
    // FutureDB is the core engine for this project and owns the ActiveSystem list
    String engineId = projectProperties.getDecommissionEngineId();
    if (engineId == null || engineId.trim().isEmpty()) {
      throw new IllegalStateException("decommissionEngineId is not configured in project.properties");
    }

    LOGGER.info("ListActiveSystems: querying engine={}", engineId);

    String query =
        "SELECT DISTINCT ?System WHERE {"
        + " ?System <http://www.w3.org/1999/02/22-rdf-syntax-ns#type>"
        + "   <" + CONCEPT_ACTIVE_SYSTEM + "> ."
        + "}";

    QueryExecutor executor = new QueryExecutor(engineId);
    List<Map<String, String>> rows = executor.executeSelect(query);

    List<Map<String, Object>> result = new ArrayList<>();
    for (Map<String, String> row : rows) {
      String uri = row.get("System");
      if (uri == null || uri.trim().isEmpty()) continue;

      String label = localName(uri).replace('_', ' ');

      Map<String, Object> entry = new HashMap<>();
      entry.put("uri", uri);
      entry.put("label", label);
      result.add(entry);
    }

    // Sort alphabetically by label (mirrors the legacy dropdown order)
    result.sort((a, b) -> ((String) a.get("label")).compareToIgnoreCase((String) b.get("label")));

    LOGGER.info("ListActiveSystems: returning {} systems", result.size());
    return new NounMetadata(result, PixelDataType.CUSTOM_DATA_STRUCTURE);
  }

  private static String localName(String uri) {
    if (uri == null) return "";
    int slash = uri.lastIndexOf('/');
    int hash = uri.lastIndexOf('#');
    int idx = Math.max(slash, hash);
    return idx >= 0 ? uri.substring(idx + 1) : uri;
  }

  @Override
  public String getReactorDescription() {
    return "Returns the sorted list of ActiveSystem nodes for the system selector dropdown.";
  }
}
