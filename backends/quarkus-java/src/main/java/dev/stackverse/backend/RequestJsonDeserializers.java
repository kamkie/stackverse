package dev.stackverse.backend;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public final class RequestJsonDeserializers {
    private RequestJsonDeserializers() {}

    public static final class TextValue extends JsonDeserializer<String> {
        @Override
        public String deserialize(JsonParser parser, DeserializationContext context)
                throws IOException {
            if (parser.hasToken(JsonToken.VALUE_STRING)) {
                return parser.getText();
            }
            parser.skipChildren();
            return null;
        }
    }

    public static final class VisibilityValue extends JsonDeserializer<String> {
        @Override
        public String deserialize(JsonParser parser, DeserializationContext context)
                throws IOException {
            if (parser.hasToken(JsonToken.VALUE_STRING)) {
                return parser.getText();
            }
            parser.skipChildren();
            return "";
        }
    }

    public static final class TagsValue extends JsonDeserializer<List<String>> {
        @Override
        public List<String> deserialize(JsonParser parser, DeserializationContext context)
                throws IOException {
            if (!parser.hasToken(JsonToken.START_ARRAY)) {
                parser.skipChildren();
                return null;
            }
            List<String> tags = new ArrayList<>();
            while (parser.nextToken() != JsonToken.END_ARRAY) {
                if (parser.hasToken(JsonToken.VALUE_STRING)) {
                    tags.add(parser.getText());
                } else {
                    parser.skipChildren();
                    tags.add("");
                }
            }
            return tags;
        }
    }
}
