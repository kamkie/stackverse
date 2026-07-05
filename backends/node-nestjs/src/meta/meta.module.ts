import { Module } from "@nestjs/common";
import { MetaController } from "./meta.controller.js";
import { MetaService } from "./meta.service.js";

@Module({
  controllers: [MetaController],
  providers: [MetaService],
})
export class MetaModule {}
