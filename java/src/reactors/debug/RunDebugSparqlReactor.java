package reactors.debug;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import prerna.sablecc2.om.PixelDataType;
import prerna.sablecc2.om.nounmeta.NounMetadata;
import reactors.AbstractProjectReactor;
import util.QueryExecutor;

/**
 * Debug-only reactor for running ad-hoc SPARQL SELECT queries with row caps.
 *
 * <p>Pixel call:
 * <pre>
 *   RunDebugSparql(
 *     engineId=["6897e1e5-3604-464d-bf26-c675eac23d26"],
 *     query=["SELECT ..."],
 *     maxRows=[300]
 *   );
 * </pre>
 */
public class RunDebugSparqlReactor extends AbstractProjectReactor {

  private static final Logger LOGGER = LogManager.getLogger(RunDebugSparqlReactor.class);

  private static final String ENGINE_ID_KEY = "engineId";
  private static final String QUERY_KEY = "query";
  private static final String MAX_ROWS_KEY = "maxRows";

  public RunDebugSparqlReactor() {
    this.keysToGet = new String[] { ENGINE_ID_KEY, QUERY_KEY, MAX_ROWS_KEY };
    this.keyRequired = new int[] { 1, 1, 0 };
  }

  @Override
  protected NounMetadata doExecute() {
    String engineId = this.keyValue.get(ENGINE_ID_KEY);
    String query = this.keyValue.get(QUERY_KEY);
    int maxRows = parsePositiveInt(this.keyValue.get(MAX_ROWS_KEY), 500);

    if (query == null || query.trim().isEmpty()) {
      throw new IllegalArgumentException("query parameter is required");
    }

    String trimmedQuery = query.trim();
    if (!trimmedQuery.toUpperCase().startsWith("SELECT")) {
      throw new IllegalArgumentException("RunDebugSparql only supports SELECT queries");
    }

    QueryExecutor executor = new QueryExecutor(engineId);
    List<Map<String, String>> rows = executor.executeSelect(trimmedQuery);

    boolean truncated = rows.size() > maxRows;
    List<Map<String, String>> outRows = truncated ? rows.subList(0, maxRows) : rows;

    Map<String, Object> out = new LinkedHashMap<>();
    out.put("engineId", engineId);
    out.put("rowCount", rows.size());
    out.put("maxRows", maxRows);
    out.put("truncated", truncated);
    out.put("rows", new ArrayList<>(outRows));

    LOGGER.info(
        "RunDebugSparql: engine={} rows={} returned={} truncated={}",
        engineId, rows.size(), outRows.size(), truncated);

    return new NounMetadata(out, PixelDataType.CUSTOM_DATA_STRUCTURE);
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

  @Override
  public String getReactorDescription() {
    return "Debug helper: executes ad-hoc SPARQL SELECT with row caps.";
  }
}
