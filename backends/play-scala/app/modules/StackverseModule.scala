package modules

import com.google.inject.AbstractModule
import services.StackverseBackend

class StackverseModule extends AbstractModule {
  override def configure(): Unit = {
    bind(classOf[StackverseBackend]).asEagerSingleton()
  }
}
