import { Module } from "@nestjs/common";
import { BookmarksController } from "./bookmarks.controller.js";
import { BookmarksService } from "./bookmarks.service.js";

@Module({
  controllers: [BookmarksController],
  providers: [BookmarksService],
})
export class BookmarksModule {}
