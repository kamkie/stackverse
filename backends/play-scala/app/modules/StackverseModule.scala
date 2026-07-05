package modules

import config.{BackendConfig, BackendConfigProvider}
import com.google.inject.AbstractModule
import services.StackverseBackend

class StackverseModule extends AbstractModule {
  override def configure(): Unit = {
    bind(classOf[BackendConfig]).toProvider(classOf[BackendConfigProvider]).asEagerSingleton()
    bind(classOf[StackverseBackend]).asEagerSingleton()
  }
}
