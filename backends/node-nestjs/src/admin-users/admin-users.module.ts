import { Module } from "@nestjs/common";
import { AdminUsersController } from "./admin-users.controller.js";
import { AdminUsersService } from "./admin-users.service.js";

@Module({
  controllers: [AdminUsersController],
  providers: [AdminUsersService],
})
export class AdminUsersModule {}
