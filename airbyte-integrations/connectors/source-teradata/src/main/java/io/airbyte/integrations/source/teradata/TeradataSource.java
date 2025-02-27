/*
 * Copyright (c) 2023 Airbyte, Inc., all rights reserved.
 */

package io.airbyte.integrations.source.teradata;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.ImmutableMap;
import io.airbyte.cdk.db.factory.DataSourceFactory;
import io.airbyte.cdk.db.jdbc.JdbcDatabase;
import io.airbyte.cdk.db.jdbc.JdbcUtils;
import io.airbyte.cdk.db.jdbc.StreamingJdbcDatabase;
import io.airbyte.cdk.db.jdbc.streaming.AdaptiveStreamingQueryConfig;
import io.airbyte.cdk.integrations.base.IntegrationRunner;
import io.airbyte.cdk.integrations.base.Source;
import io.airbyte.cdk.integrations.source.jdbc.AbstractJdbcSource;
import io.airbyte.cdk.integrations.source.jdbc.JdbcDataSourceUtils;
import io.airbyte.cdk.integrations.source.relationaldb.TableInfo;
import io.airbyte.commons.json.Jsons;
import io.airbyte.commons.map.MoreMaps;
import io.airbyte.protocol.models.CommonField;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.sql.JDBCType;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TeradataSource extends AbstractJdbcSource<JDBCType> implements Source {

  private static final Logger LOGGER = LoggerFactory.getLogger(TeradataSource.class);

  private static final int INTERMEDIATE_STATE_EMISSION_FREQUENCY = 10_000;

  static final String DRIVER_CLASS = "com.teradata.jdbc.TeraDriver";

  public static final String PARAM_MODE = "mode";
  public static final String PARAM_SSL = "ssl";
  public static final String PARAM_SSL_MODE = "ssl_mode";
  public static final String PARAM_SSLMODE = "sslmode";
  public static final String PARAM_SSLCA = "sslca";
  public static final String REQUIRE = "require";

  private static final String CA_CERTIFICATE = "ca.pem";
  private static final String LOG_MECH = "logmech";
  private static final String AUTH_TYPE = "auth_type";
  private static final String TD2_LOG_MECH = "TD2";
  private static final String BROWSER_LOG_MECH = "BROWSER";

  public TeradataSource() {
    super(DRIVER_CLASS, AdaptiveStreamingQueryConfig::new, new TeradataSourceOperations());
  }

  public static void main(final String[] args) throws Exception {
    final Source source = new TeradataSource();
    LOGGER.info("starting source: {}", TeradataSource.class);
    new IntegrationRunner(source).run(args);
    LOGGER.info("completed source: {}", TeradataSource.class);
  }

  @Override
  public JsonNode toDatabaseConfig(final JsonNode config) {
    final String schema = config.get(JdbcUtils.DATABASE_KEY).asText();

    final String host = config.get(JdbcUtils.HOST_KEY).asText();

    final String jdbcUrl = String.format("jdbc:teradata://%s/", host);

    final ImmutableMap.Builder<Object, Object> configBuilder = ImmutableMap.builder()
        .put(JdbcUtils.JDBC_URL_KEY, jdbcUrl)
        .put(JdbcUtils.SCHEMA_KEY, schema);

    String userName = null;
    String password = null;
    if (config.has(LOG_MECH) && config.get(LOG_MECH).has(AUTH_TYPE) && !(config.get(LOG_MECH).get(AUTH_TYPE).asText().equals(BROWSER_LOG_MECH))) {
      userName = config.get(LOG_MECH).get(JdbcUtils.USERNAME_KEY).asText();
      password = config.get(LOG_MECH).get(JdbcUtils.PASSWORD_KEY).asText();
    }

    if (userName != null) {
      configBuilder.put(JdbcUtils.USERNAME_KEY, userName);
    }
    if (password != null) {
      configBuilder.put(JdbcUtils.PASSWORD_KEY, password);
    }

    if (config.has(JdbcUtils.JDBC_URL_PARAMS_KEY)) {
      configBuilder.put(JdbcUtils.JDBC_URL_PARAMS_KEY, config.get(JdbcUtils.JDBC_URL_PARAMS_KEY).asText());
    }

    return Jsons.jsonNode(configBuilder.build());
  }

  @Override
  public Set<String> getExcludedInternalNameSpaces() {
    // the connector requires to have a database explicitly defined
    return Set.of("");
  }

  @Override
  protected int getStateEmissionFrequency() {
    return INTERMEDIATE_STATE_EMISSION_FREQUENCY;
  }

  @Override
  public List<TableInfo<CommonField<JDBCType>>> discoverInternal(JdbcDatabase database) throws Exception {
    return discoverInternal(database,
        database.getSourceConfig().has(JdbcUtils.DATABASE_KEY) ? database.getSourceConfig().get(JdbcUtils.DATABASE_KEY).asText() : null);
  }

  @Override
  public JdbcDatabase createDatabase(JsonNode sourceConfig) throws SQLException {
    final Map<String, String> customProperties = JdbcUtils.parseJdbcParameters(sourceConfig, JdbcUtils.JDBC_URL_PARAMS_KEY);
    final Map<String, String> sslConnectionProperties = getSslConnectionProperties(sourceConfig);
    JdbcDataSourceUtils.assertCustomParametersDontOverwriteDefaultParameters(customProperties, sslConnectionProperties);

    final JsonNode jdbcConfig = toDatabaseConfig(sourceConfig);
    Map<String, String> connectionProperties = MoreMaps.merge(customProperties, sslConnectionProperties);
    connectionProperties = MoreMaps.merge(appendLogMech(jdbcConfig), connectionProperties);
    LOGGER.info("Source Satish -{} ", connectionProperties);
    // Create the data source
    final DataSource dataSource = DataSourceFactory.create(
        jdbcConfig.has(JdbcUtils.USERNAME_KEY) ? jdbcConfig.get(JdbcUtils.USERNAME_KEY).asText() : null,
        jdbcConfig.has(JdbcUtils.PASSWORD_KEY) ? jdbcConfig.get(JdbcUtils.PASSWORD_KEY).asText() : null,
        driverClassName,
        jdbcConfig.get(JdbcUtils.JDBC_URL_KEY).asText(),
        connectionProperties,
        getConnectionTimeout(connectionProperties, driverClassName));
    // Record the data source so that it can be closed.
    dataSources.add(dataSource);

    final JdbcDatabase database = new StreamingJdbcDatabase(
        dataSource,
        sourceOperations,
        streamingQueryConfigProvider);

    quoteString = (quoteString == null ? database.getMetaData().getIdentifierQuoteString() : quoteString);
    database.setSourceConfig(sourceConfig);
    database.setDatabaseConfig(jdbcConfig);
    return database;
  }

  /** Appends Logging Mechanism to JDBC URL */
  private Map<String, String> appendLogMech(JsonNode config) {
    Map<String, String> logmechParams = new HashMap<>();

    if (config.has(LOG_MECH) &&
        config.get(LOG_MECH).has(AUTH_TYPE) &&
        !config.get(LOG_MECH).get(AUTH_TYPE).asText().equals(TD2_LOG_MECH)) {

      logmechParams.put(LOG_MECH,
          config.get(LOG_MECH).get(AUTH_TYPE).asText());
    }
    return logmechParams;
  }

  private Map<String, String> getSslConnectionProperties(JsonNode config) {
    final Map<String, String> additionalParameters = new HashMap<>();
    if (config.has(PARAM_SSL) && config.get(PARAM_SSL).asBoolean()) {
      LOGGER.debug("SSL Enabled");
      if (config.has(PARAM_SSL_MODE)) {
        LOGGER.debug("Selected SSL Mode : {}", config.get(PARAM_SSL_MODE).get(PARAM_MODE).asText());
        additionalParameters.putAll(obtainConnectionOptions(config.get(PARAM_SSL_MODE)));
      } else {
        additionalParameters.put(PARAM_SSLMODE, REQUIRE);
      }
    }
    return additionalParameters;
  }

  private Map<String, String> obtainConnectionOptions(final JsonNode encryption) {
    final Map<String, String> additionalParameters = new HashMap<>();
    if (!encryption.isNull()) {
      final var method = encryption.get(PARAM_MODE).asText();
      switch (method) {
        case "verify-ca", "verify-full" -> {
          additionalParameters.put(PARAM_SSLMODE, method);
          try {
            createCertificateFile(CA_CERTIFICATE, encryption.get("ssl_ca_certificate").asText());
          } catch (final IOException ioe) {
            throw new UncheckedIOException(ioe);
          }
          additionalParameters.put(PARAM_SSLCA, CA_CERTIFICATE);
        }
        default -> additionalParameters.put(PARAM_SSLMODE, method);
      }
    }
    return additionalParameters;
  }

  private static void createCertificateFile(String fileName, String fileValue) throws IOException {
    try (final PrintWriter out = new PrintWriter(fileName, StandardCharsets.UTF_8)) {
      out.print(fileValue);
    }
  }

}
