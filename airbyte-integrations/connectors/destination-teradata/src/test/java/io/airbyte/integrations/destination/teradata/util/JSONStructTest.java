package io.airbyte.integrations.destination.teradata.util;

import com.fasterxml.jackson.databind.JsonNode;
import io.airbyte.cdk.db.jdbc.JdbcUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class JSONStructTest {
    private JSONStruct struct;
    private final String json = "{\n" +
            "\t\"id\":123,\n" +
            "\t\"name\":\"Pankaj Kumar\",\n" +
            "}";
    @BeforeEach
    void setup() {
        struct = new JSONStruct("JSON",new Object[] {json});
    }

    @Test
    void testGetAttributes() throws SQLException {
        assertEquals(json, struct.getAttributes()[0]);
    }

    @Test
    void testGetAttributesException() {
        SQLException exception = assertThrows(SQLException.class, () -> {
            Map<Integer, String> map = new HashMap<>();
            struct.getAttributes(map);
        });
        String expectedMessage = "getAttributes (Map) NOT SUPPORTED";
        String actualMessage = exception.getMessage();
        assertTrue(actualMessage.contains(expectedMessage));
    }

    @Test
    void testGetSQLTypeName() throws SQLException {
        assertEquals("JSON", struct.getSQLTypeName());
    }
}
