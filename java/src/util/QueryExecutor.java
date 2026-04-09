package util;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import prerna.engine.api.IDatabaseEngine;
import prerna.engine.api.IHeadersDataRow;
import prerna.engine.api.IRawSelectWrapper;
import prerna.masterdatabase.utility.MasterDatabaseUtility;
import prerna.rdf.engine.wrappers.WrapperManager;
import prerna.util.Utility;

/**
 * Utility class for executing SPARQL SELECT queries via WrapperManager.
 *
 * <p>Usage:
 * <pre>
 *   QueryExecutor executor = new QueryExecutor(engineId);
 *   List&lt;Map&lt;String, String&gt;&gt; results = executor.executeSelect(sparqlQuery);
 * </pre>
 */
public class QueryExecutor {

  private static final Logger LOGGER = LogManager.getLogger(QueryExecutor.class);

  private String engineId;
  private IDatabaseEngine engine;

  public QueryExecutor(String engineId) {
    this.engineId = engineId;
    resolveEngine();
  }

  private void resolveEngine() {
    if (engineId == null || engineId.trim().isEmpty()) {
      throw new IllegalArgumentException("Engine ID cannot be null or empty");
    }

    String resolvedId = MasterDatabaseUtility.testDatabaseIdIfAlias(engineId);
    this.engine = Utility.getDatabase(resolvedId);

    if (this.engine == null) {
      throw new IllegalArgumentException("Cannot resolve engine with ID: " + engineId);
    }

    LOGGER.debug("QueryExecutor initialized with engine: " + resolvedId);
  }

  /**
   * Execute a SPARQL SELECT query and return results as a list of row maps.
   * Each row is a Map where keys are SPARQL variable names and values are
   * the bound URIs or literals.
   */
  public List<Map<String, String>> executeSelect(String query) {
    List<Map<String, String>> results = new ArrayList<>();

    if (query == null || query.trim().isEmpty()) {
      throw new IllegalArgumentException("Query cannot be null or empty");
    }

    IRawSelectWrapper wrapper = null;
    try {
      LOGGER.debug("Executing SPARQL query against engine: " + engineId);

      wrapper = WrapperManager.getInstance().getRawWrapper(engine, query);

      if (wrapper == null) {
        throw new RuntimeException("Failed to obtain query wrapper from WrapperManager");
      }

      String[] variableNames = wrapper.getHeaders();

      if (variableNames == null || variableNames.length == 0) {
        LOGGER.warn("Query returned no variables. Query: " + query);
        return results;
      }

      while (wrapper.hasNext()) {
        IHeadersDataRow statement = wrapper.next();
        Map<String, String> row = new TreeMap<>();
        Object[] values = statement.getRawValues();
        if (values == null) {
          values = statement.getValues();
        }

        for (int i = 0; i < variableNames.length; i++) {
          if (values == null || i >= values.length) {
            continue;
          }
          Object value = values[i];
          if (value != null) {
            row.put(variableNames[i], value.toString());
          }
        }

        if (!row.isEmpty()) {
          results.add(row);
        }
      }

      LOGGER.debug("Query completed. Returned " + results.size() + " rows");

    } catch (Exception e) {
      LOGGER.error("SPARQL query execution failed. Engine: " + engineId + ", Query: " + query, e);
      throw new RuntimeException("Query execution error: " + e.getMessage(), e);
    } finally {
      if (wrapper != null) {
        try {
          wrapper.close();
        } catch (Exception closeEx) {
          LOGGER.warn("Failed to close raw query wrapper cleanly", closeEx);
        }
      }
    }

    return results;
  }

  public IDatabaseEngine getEngine() {
    return this.engine;
  }

  public String getEngineId() {
    return this.engineId;
  }
}
