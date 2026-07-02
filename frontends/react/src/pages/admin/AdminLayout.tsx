import { NavLink, Outlet } from "react-router";
import { isAdmin, isModerator, useMe } from "../../auth/useMe";
import { useSession } from "../../auth/session";
import { Loading, LoginPrompt } from "../../components/states";
import { useI18n } from "../../i18n/I18nProvider";

/**
 * Role-gated admin shell: navigation shows only what the caller's roles from
 * `/api/v1/me` allow — moderators see dashboard + reports, admins everything.
 */
export function AdminLayout() {
  const { t } = useI18n();
  const session = useSession();
  const me = useMe();

  if (session.isPending || (session.data?.authenticated && me.isPending)) {
    return <Loading />;
  }
  if (!session.data?.authenticated) return <LoginPrompt />;
  if (!isModerator(me.data)) {
    return (
      <div className="sv-alert sv-alert--danger" role="alert">
        403
      </div>
    );
  }

  const navLinkClass = ({ isActive }: { isActive: boolean }) =>
    `sv-nav-link${isActive ? " is-active" : ""}`;

  return (
    <div className="sv-layout">
      <aside className="sv-sidebar">
        <h2 className="sv-sidebar-title">{t("ui.nav.admin")}</h2>
        <nav className="sv-nav sv-nav--vertical" aria-label={t("ui.nav.admin")}>
          <NavLink to="/admin" end className={navLinkClass}>
            {t("ui.admin.dashboard")}
          </NavLink>
          <NavLink to="/admin/reports" className={navLinkClass}>
            {t("ui.admin.reports")}
          </NavLink>
          {isAdmin(me.data) && (
            <>
              <NavLink to="/admin/users" className={navLinkClass}>
                {t("ui.admin.users")}
              </NavLink>
              <NavLink to="/admin/audit" className={navLinkClass}>
                {t("ui.admin.audit")}
              </NavLink>
              <NavLink to="/admin/messages" className={navLinkClass}>
                {t("ui.admin.messages")}
              </NavLink>
            </>
          )}
        </nav>
      </aside>
      <section className="sv-content">
        <Outlet />
      </section>
    </div>
  );
}
