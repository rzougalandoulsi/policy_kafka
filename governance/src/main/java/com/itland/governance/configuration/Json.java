package com.itland.governance.configuration;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

public final class Json {

  private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};
  private static final TypeReference<List<Map<String, Object>>> LIST_OF_MAPS = new TypeReference<>() {};
  private static final TypeReference<List<String>> LIST_OF_STRINGS = new TypeReference<>() {};

  private final JsonMapper mapper;

  public Json(JsonMapper mapper) {
    this.mapper = mapper;
  }

  public String write(Object value) {
    return mapper.writeValueAsString(value);
  }

  public <T> T read(String json, Class<T> type) {
    return mapper.readValue(json, type);
  }

  public <T> T read(String json, TypeReference<T> type) {
    return mapper.readValue(json, type);
  }

  public Map<String, Object> readMap(String json) {
    return json == null ? Map.of() : mapper.readValue(json, MAP);
  }

  public List<Map<String, Object>> readListOfMaps(String json) {
    return json == null ? List.of() : mapper.readValue(json, LIST_OF_MAPS);
  }

  public List<String> readStrings(String json) {
    return json == null ? List.of() : mapper.readValue(json, LIST_OF_STRINGS);
  }

  public <T> T convert(Object value, Class<T> type) {
    return mapper.convertValue(value, type);
  }

  public Map<String, Object> toMap(Object value) {
    return mapper.convertValue(value, MAP);
  }

  public JsonMapper mapper() {
    return mapper;
  }
}
