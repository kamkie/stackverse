import { Module } from "@nestjs/common";
import { APP_FILTER, APP_GUARD } from "@nestjs/core";
import { AdminUsersModule } from "./admin-users/admin-users.module.js";
import { AuditLogModule } from "./audit-log/audit-log.module.js";
import { AuthorizationGuard, BearerAuthGuard } from "./auth.js";
import { BookmarksModule } from "./bookmarks/bookmarks.module.js";
import { MessagesModule } from "./messages/messages.module.js";
import { MetaModule } from "./meta/meta.module.js";
import { ModerationModule } from "./moderation/moderation.module.js";
import { ProblemFilter } from "./problem.filter.js";
import { StatsModule } from "./stats/stats.module.js";

@Module({
  imports: [
    BookmarksModule,
    MessagesModule,
    ModerationModule,
    AdminUsersModule,
    AuditLogModule,
    StatsModule,
    MetaModule,
  ],
  providers: [
    { provide: APP_GUARD, useClass: BearerAuthGuard },
    { provide: APP_GUARD, useClass: AuthorizationGuard },
    { provide: APP_FILTER, useClass: ProblemFilter },
  ],
})
export class AppModule {}
