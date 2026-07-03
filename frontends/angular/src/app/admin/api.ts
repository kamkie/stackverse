import { HttpClient } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { call, params } from '../api/http';
import type {
  AdminStats,
  AuditEntry,
  Message,
  MessageInput,
  Page,
  Report,
  ReportResolutionInput,
  ReportStatus,
  UserAccount,
  UserStatusInput,
} from '../api/types';

export interface AuditQuery {
  actor?: string;
  action?: string;
  from?: string;
  to?: string;
  page: number;
}

export interface MessagesQuery {
  q?: string;
  language?: string;
  page: number;
}

@Injectable({ providedIn: 'root' })
export class AdminApi {
  private readonly http = inject(HttpClient);

  getStats(): Promise<AdminStats> {
    return call(this.http.get<AdminStats>('/api/v1/admin/stats'));
  }

  listReports(status: ReportStatus, page: number): Promise<Page<Report>> {
    return call(
      this.http.get<Page<Report>>('/api/v1/admin/reports', { params: params({ status, page }) }),
    );
  }

  resolveReport(id: string, body: ReportResolutionInput): Promise<Report> {
    return call(this.http.put<Report>(`/api/v1/admin/reports/${id}`, body));
  }

  listUsers(q: string, page: number): Promise<Page<UserAccount>> {
    return call(
      this.http.get<Page<UserAccount>>('/api/v1/admin/users', { params: params({ q, page }) }),
    );
  }

  setUserStatus(username: string, body: UserStatusInput): Promise<UserAccount> {
    return call(
      this.http.put<UserAccount>(
        `/api/v1/admin/users/${encodeURIComponent(username)}/status`,
        body,
      ),
    );
  }

  listAuditEntries(query: AuditQuery): Promise<Page<AuditEntry>> {
    return call(
      this.http.get<Page<AuditEntry>>('/api/v1/admin/audit-log', {
        params: params({ ...query }),
      }),
    );
  }

  listMessages(query: MessagesQuery): Promise<Page<Message>> {
    return call(
      this.http.get<Page<Message>>('/api/v1/messages', { params: params({ ...query }) }),
    );
  }

  createMessage(body: MessageInput): Promise<Message> {
    return call(this.http.post<Message>('/api/v1/messages', body));
  }

  updateMessage(id: string, body: MessageInput): Promise<Message> {
    return call(this.http.put<Message>(`/api/v1/messages/${id}`, body));
  }

  deleteMessage(id: string): Promise<void> {
    return call(this.http.delete<void>(`/api/v1/messages/${id}`));
  }
}
