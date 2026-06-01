package reactors.compositionTimeline;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import prerna.sablecc2.om.PixelDataType;
import prerna.sablecc2.om.nounmeta.NounMetadata;
import reactors.AbstractProjectReactor;
import util.QueryExecutor;

/**
 * Returns the list of ActiveSystem nodes from FutureDB for use in the system selector dropdown.
 *
 * <p>Queries TAP_Core_Data (baseEngineId) for ActiveSystem instances (which are declared in its
 * OWL), then cross-references against FutureDB (decommissionEngineId) System instances, returning
 * only systems present in both. This is resilient to FutureDB loader-sheet round-trips, which
 * silently drop ActiveSystem type triples because ActiveSystem is not declared in FutureDB's OWL.
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
    String futureDbId = projectProperties.getDecommissionEngineId();
    String tapCoreId = projectProperties.getBaseEngineId();

    if (futureDbId == null || futureDbId.trim().isEmpty()) {
      throw new IllegalStateException("decommissionEngineId is not configured in project.properties");
    }
    if (tapCoreId == null || tapCoreId.trim().isEmpty()) {
      throw new IllegalStateException("baseEngineId is not configured in project.properties");
    }

    LOGGER.info("ListActiveSystems: querying TAP_Core_Data={} for active names, FutureDB={} for system URIs", tapCoreId, futureDbId);

    // Step 1: Get ActiveSystem local names from TAP_Core_Data (which declares ActiveSystem in its OWL)
    String activeQuery =
        "SELECT DISTINCT ?System WHERE {"
        + " ?System <http://www.w3.org/1999/02/22-rdf-syntax-ns#type>"
        + "   <" + CONCEPT_ACTIVE_SYSTEM + "> ."
        + "}";
    QueryExecutor tapExecutor = new QueryExecutor(tapCoreId);
    List<Map<String, String>> activeRows = tapExecutor.executeSelect(activeQuery);

    Set<String> activeNames = new HashSet<>();
    for (Map<String, String> row : activeRows) {
      String uri = row.get("System");
      if (uri != null) activeNames.add(localName(uri));
    }
    LOGGER.info("ListActiveSystems: found {} active system names in TAP_Core_Data", activeNames.size());

    // Step 2: Get all System instances from FutureDB
    String systemQuery =
        "SELECT DISTINCT ?System WHERE {"
        + " ?System <http://www.w3.org/1999/02/22-rdf-syntax-ns#type>"
        + "   <http://semoss.org/ontologies/Concept/System> ."
        + "}";
    QueryExecutor futureExecutor = new QueryExecutor(futureDbId);
    List<Map<String, String>> systemRows = futureExecutor.executeSelect(systemQuery);

    // Step 3: Keep only FutureDB Systems whose local name matches an ActiveSystem in TAP_Core_Data
    List<Map<String, Object>> result = new ArrayList<>();
    for (Map<String, String> row : systemRows) {
      String uri = row.get("System");
      if (uri == null || uri.trim().isEmpty()) continue;

      String name = localName(uri);
      if (!activeNames.contains(name)) continue;

      Map<String, Object> entry = new HashMap<>();
      entry.put("uri", uri);
      entry.put("label", name.replace('_', ' '));
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
