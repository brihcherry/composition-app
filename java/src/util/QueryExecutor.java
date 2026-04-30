package util;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import prerna.engine.api.IDatabaseEngine;
import prerna.engine.api.ISelectStatement;
import prerna.engine.api.ISelectWrapper;
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
    * Uses the legacy select wrapper path so row values mirror the playsheet
    * behavior more closely than the raw wrapper path.
   */
  public List<Map<String, String>> executeSelect(String query) {
    List<Map<String, String>> results = new ArrayList<>();

    if (query == null || query.trim().isEmpty()) {
      throw new IllegalArgumentException("Query cannot be null or empty");
    }

    ISelectWrapper wrapper = null;
    try {
      LOGGER.debug("Executing SPARQL query against engine: " + engineId);

      wrapper = WrapperManager.getInstance().getSWrapper(engine, query);

      if (wrapper == null) {
        throw new RuntimeException("Failed to obtain query wrapper from WrapperManager");
      }

      String[] variableNames = wrapper.getVariables();

      if (variableNames == null || variableNames.length == 0) {
        LOGGER.warn("Query returned no variables. Query: " + query);
        return results;
      }

      while (wrapper.hasNext()) {
        ISelectStatement statement = wrapper.next();
        Map<String, String> row = new TreeMap<>();

        for (String variableName : variableNames) {
          Object rawValue = statement.getRawVar(variableName);
          Object displayValue = statement.getVar(variableName);
          String value = chooseValue(rawValue, displayValue);
          if (value != null) {
            row.put(variableName, value);
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
          LOGGER.warn("Failed to close select query wrapper cleanly", closeEx);
        }
      }
    }

    return results;
  }

  private String chooseValue(Object rawValue, Object displayValue) {
    if (rawValue != null) {
      String rawText = rawValue.toString();
      if (looksLikeUri(rawText)) {
        return rawText;
      }
    }

    if (displayValue != null) {
      return displayValue.toString();
    }

    return rawValue != null ? rawValue.toString() : null;
  }

  private boolean looksLikeUri(String value) {
    return value.startsWith("http://")
        || value.startsWith("https://")
        || value.startsWith("urn:")
        || value.startsWith("file:");
  }

  public IDatabaseEngine getEngine() {
    return this.engine;
  }

  public String getEngineId() {
    return this.engineId;
  }
}
