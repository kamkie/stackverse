package dev.stackverse.openliberty;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.NotAllowedException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Provider
public class ProblemMapper implements ExceptionMapper<Throwable> {
  @Context
  UriInfo uriInfo;

  @Context
  HttpHeaders headers;

  @Context
  HttpServletRequest request;

  @Override
  public Response toResponse(Throwable throwable) {
    Throwable ex = unwrap(throwable);
    if (ex instanceof ValidationProblem validation) {
      String language = StackverseResource.resolveLanguage(
          StackverseResource.firstParam(uriInfo.getQueryParameters().get("lang")),
          headers.getHeaderString("Accept-Language"));
      List<Map<String, Object>> errors = new ArrayList<>();
      for (FieldViolation violation : validation.violations) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("field", violation.field());
        item.put("messageKey", violation.messageKey());
        item.put("message", StackverseResource.localize(violation.messageKey(), language));
        errors.add(item);
      }
      return JsonSupport.problem(400, "Bad Request", "Validation failed", errors);
    }
    if (ex instanceof ApiProblem problem) {
      String detail = problem.detail;
      if (problem.detailKey != null) {
        String language = StackverseResource.resolveLanguage(
            StackverseResource.firstParam(uriInfo.getQueryParameters().get("lang")),
            headers.getHeaderString("Accept-Language"));
        detail = StackverseResource.localize(problem.detailKey, language);
      }
      return JsonSupport.problem(problem.status, problem.title, detail, null);
    }
    if (ex instanceof NotAllowedException) {
      return JsonSupport.problem(405, "Method Not Allowed", null, null);
    }
    if (ex instanceof NotFoundException) {
      return JsonSupport.problem(404, "Not Found", null, null);
    }
    if (ex instanceof WebApplicationException web) {
      int status = web.getResponse().getStatus();
      return JsonSupport.problem(status, Response.Status.fromStatusCode(status).getReasonPhrase(), null, null);
    }
    Log.event("error", "request_failed", "failure", "Unhandled request failure",
        Map.of("error_code", ex.getClass().getSimpleName()));
    return JsonSupport.problem(500, "Internal Server Error", "Unexpected server error.", null);
  }

  private Throwable unwrap(Throwable throwable) {
    if (throwable instanceof RuntimeException runtime && runtime.getCause() instanceof ApiProblem) {
      return runtime.getCause();
    }
    return throwable;
  }
}
