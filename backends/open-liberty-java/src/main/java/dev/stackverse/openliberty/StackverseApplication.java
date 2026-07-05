package dev.stackverse.openliberty;

import jakarta.ws.rs.ApplicationPath;
import jakarta.ws.rs.core.Application;
import java.util.Set;

@ApplicationPath("/")
public class StackverseApplication extends Application {
  public StackverseApplication() {
    RuntimeSupport.boot();
  }

  @Override
  public Set<Class<?>> getClasses() {
    return Set.of(StackverseResource.class, AuthFilter.class, ProblemMapper.class);
  }
}
