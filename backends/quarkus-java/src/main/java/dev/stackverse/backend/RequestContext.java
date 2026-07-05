package dev.stackverse.backend;

import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.UriInfo;

record RequestContext(UriInfo uriInfo, HttpHeaders headers) {}
