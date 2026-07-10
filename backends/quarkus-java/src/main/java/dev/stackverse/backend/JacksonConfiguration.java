package dev.stackverse.backend;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.type.LogicalType;
import io.quarkus.jackson.ObjectMapperCustomizer;
import jakarta.inject.Singleton;

@Singleton
class JacksonConfiguration implements ObjectMapperCustomizer {
    @Override
    public void customize(ObjectMapper mapper) {
        var textualCoercion = mapper.coercionConfigFor(LogicalType.Textual);
        textualCoercion.setCoercion(CoercionInputShape.Integer, CoercionAction.Fail);
        textualCoercion.setCoercion(CoercionInputShape.Float, CoercionAction.Fail);
        textualCoercion.setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail);
    }
}
