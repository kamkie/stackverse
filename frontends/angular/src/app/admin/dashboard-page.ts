import { Component, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import type { AdminStats } from '../api/types';
import { Query } from '../core/query';
import { I18n } from '../i18n/i18n';
import { ErrorState, Loading } from '../shared/states';
import { AdminApi } from './api';

interface Bar {
  date: string;
  createdX: number;
  createdY: number;
  createdHeight: number;
  activeX: number;
  activeY: number;
  activeHeight: number;
  width: number;
  tooltip: string;
}

/** Grouped-bar SVG chart geometry for the 30-day series (styled by sv-chart classes). */
function chartBars(daily: AdminStats['daily'], t: (key: string) => string): Bar[] {
  const max = Math.max(1, ...daily.map((d) => Math.max(d.bookmarksCreated, d.activeUsers)));
  const slot = (CHART.width - CHART.left) / Math.max(1, daily.length);
  const barWidth = Math.max(2, slot / 2 - 1.5);
  const chartHeight = CHART.height - CHART.bottom - CHART.top;
  return daily.map((day, i) => {
    const x = CHART.left + i * slot;
    const created = (day.bookmarksCreated / max) * chartHeight;
    const active = (day.activeUsers / max) * chartHeight;
    return {
      date: day.date,
      createdX: x,
      createdY: CHART.top + chartHeight - created,
      createdHeight: created,
      activeX: x + barWidth + 1,
      activeY: CHART.top + chartHeight - active,
      activeHeight: active,
      width: barWidth,
      tooltip: `${day.date}: ${day.bookmarksCreated} ${t('ui.admin.stats.bookmarks-created')}, ${day.activeUsers} ${t('ui.admin.stats.active-users')}`,
    };
  });
}

const CHART = { width: 620, height: 160, left: 24, bottom: 18, top: 6 };

@Component({
  selector: 'app-dashboard-page',
  imports: [ErrorState, Loading, RouterLink],
  template: `
    @if (stats.pending()) {
      <sv-loading />
    } @else if (stats.error() !== null) {
      <sv-error-state [error]="stats.error()" />
    } @else if (stats.data(); as data) {
      <h1 class="sv-page-title">{{ t('ui.admin.dashboard') }}</h1>
      <div class="sv-stats-grid">
        <div class="sv-stat">
          <span class="sv-stat-value">{{ data.totals.users }}</span>
          <span class="sv-stat-label">{{ t('ui.admin.stats.users') }}</span>
        </div>
        <div class="sv-stat">
          <span class="sv-stat-value">{{ data.totals.bookmarks }}</span>
          <span class="sv-stat-label">{{ t('ui.admin.stats.bookmarks') }}</span>
        </div>
        <div class="sv-stat">
          <span class="sv-stat-value">{{ data.totals.publicBookmarks }}</span>
          <span class="sv-stat-label">{{ t('ui.admin.stats.public-bookmarks') }}</span>
        </div>
        <div class="sv-stat">
          <span class="sv-stat-value">{{ data.totals.hiddenBookmarks }}</span>
          <span class="sv-stat-label">{{ t('ui.admin.stats.hidden-bookmarks') }}</span>
        </div>
        <!-- Open reports is the one stat with a queue behind it — link straight there. -->
        <a routerLink="/admin/reports" class="sv-stat sv-stat--link">
          <span class="sv-stat-value">{{ data.totals.openReports }}</span>
          <!-- single line: the label is matched with an anchored regex in e2e -->
          <span class="sv-stat-label">{{ tCount('ui.admin.stats.open-reports', data.totals.openReports) }}</span>
        </a>
      </div>
      <div class="sv-card">
        <div class="sv-legend">
          <span>
            <span class="sv-legend-swatch"></span>
            {{ t('ui.admin.stats.bookmarks-created') }}
          </span>
          <span>
            <span class="sv-legend-swatch sv-legend-swatch--secondary"></span>
            {{ t('ui.admin.stats.active-users') }}
          </span>
        </div>
        <svg
          class="sv-chart"
          [attr.viewBox]="'0 0 ' + chart.width + ' ' + chart.height"
          role="img"
          [attr.aria-label]="t('ui.admin.chart.label')"
        >
          @for (bar of bars(data); track bar.date) {
            <g>
              <title>{{ bar.tooltip }}</title>
              <rect
                class="sv-chart-bar"
                [attr.x]="bar.createdX"
                [attr.y]="bar.createdY"
                [attr.width]="bar.width"
                [attr.height]="bar.createdHeight"
              />
              <rect
                class="sv-chart-bar sv-chart-bar--secondary"
                [attr.x]="bar.activeX"
                [attr.y]="bar.activeY"
                [attr.width]="bar.width"
                [attr.height]="bar.activeHeight"
              />
            </g>
          }
          <line
            class="sv-chart-axis"
            [attr.x1]="chart.left"
            [attr.y1]="chart.height - chart.bottom"
            [attr.x2]="chart.width"
            [attr.y2]="chart.height - chart.bottom"
          />
          <text class="sv-chart-label" x="0" [attr.y]="chart.top + 10">{{ chartMax(data) }}</text>
          @if (data.daily[0]; as first) {
            <text class="sv-chart-label" [attr.x]="chart.left" [attr.y]="chart.height - 4">
              {{ first.date }}
            </text>
          }
          @if (data.daily.length > 1) {
            <text
              class="sv-chart-label"
              [attr.x]="chart.width"
              [attr.y]="chart.height - 4"
              text-anchor="end"
            >
              {{ data.daily[data.daily.length - 1]?.date }}
            </text>
          }
        </svg>
      </div>
      @if (data.topTags.length > 0) {
        <div class="sv-card">
          <h2 class="sv-sidebar-title">{{ t('ui.nav.tags') }}</h2>
          <ul class="sv-tag-list">
            @for (entry of data.topTags; track entry.tag) {
              <li>
                <span class="sv-tag">
                  {{ entry.tag }} <span class="sv-tag-count">{{ entry.count }}</span>
                </span>
              </li>
            }
          </ul>
        </div>
      }
    }
  `,
})
export class DashboardPage {
  private readonly i18n = inject(I18n);
  protected readonly t = this.i18n.t;
  protected readonly tCount = this.i18n.tCount;
  private readonly api = inject(AdminApi);

  protected readonly chart = CHART;
  protected readonly stats = new Query(signal(undefined), () => this.api.getStats());

  protected bars(data: AdminStats): Bar[] {
    return chartBars(data.daily, this.t);
  }

  protected chartMax(data: AdminStats): number {
    return Math.max(1, ...data.daily.map((d) => Math.max(d.bookmarksCreated, d.activeUsers)));
  }
}
