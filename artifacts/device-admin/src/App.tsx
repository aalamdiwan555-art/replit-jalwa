import { type ReactNode, useMemo, useState } from 'react';
import { QueryClient, QueryClientProvider, useQueryClient } from '@tanstack/react-query';
import {
  Activity,
  ArrowLeft,
  ArrowUpRight,
  BatteryCharging,
  Bell,
  ChevronRight,
  CircleAlert,
  Clock3,
  Command,
  Home,
  LayoutDashboard,
  Menu,
  Pause,
  Play,
  Plus,
  RefreshCw,
  Search,
  Server,
  Settings2,
  ShieldCheck,
  Smartphone,
  Terminal,
  Wifi,
  WifiOff,
  X,
  Zap,
} from 'lucide-react';
import {
  getGetActivityQueryKey,
  getGetAdminSummaryQueryKey,
  getGetDeviceQueryKey,
  getListDeviceCommandsQueryKey,
  getListDevicesQueryKey,
  useEnrollDevice,
  useGetActivity,
  useGetAdminSummary,
  useGetDevice,
  useListDeviceCommands,
  useListDevices,
  useSendDeviceCommand,
  useUpdateDevice,
} from '@workspace/api-client-react';
import type {
  ActivityEvent,
  Device,
  DeviceCommand,
  DeviceCommandInput,
  ListDevicesParams,
} from '@workspace/api-client-react';
import { ErrorBoundary } from '@/components/error-boundary';
import { Toaster } from '@/components/ui/toaster';
import { TooltipProvider } from '@/components/ui/tooltip';
import { useToast } from '@/hooks/use-toast';
import NotFound from '@/pages/not-found';
import { Link, Route, Switch, Router as WouterRouter, useLocation, useParams } from 'wouter';

const queryClient = new QueryClient();

const navItems = [
  { href: '/', label: 'Overview', icon: LayoutDashboard },
  { href: '/devices', label: 'Devices', icon: Smartphone },
  { href: '/activity', label: 'Activity', icon: Activity },
];

const statusStyles: Record<string, string> = {
  ONLINE: 'bg-teal-100 text-teal-800 border-teal-200',
  OFFLINE: 'bg-slate-100 text-slate-700 border-slate-200',
  PAUSED: 'bg-amber-100 text-amber-800 border-amber-200',
  PENDING: 'bg-orange-100 text-orange-800 border-orange-200',
};

const statusDot: Record<string, string> = {
  ONLINE: 'bg-teal-500',
  OFFLINE: 'bg-slate-400',
  PAUSED: 'bg-amber-500',
  PENDING: 'bg-orange-500',
};

function formatRelative(date: string | null | undefined) {
  if (!date) return 'Never';
  const diff = Date.now() - new Date(date).getTime();
  if (diff < 60_000) return 'just now';
  if (diff < 3_600_000) return `${Math.max(1, Math.floor(diff / 60_000))}m ago`;
  if (diff < 86_400_000) return `${Math.floor(diff / 3_600_000)}h ago`;
  return new Intl.DateTimeFormat('en', { month: 'short', day: 'numeric' }).format(new Date(date));
}

function formatDate(date: string | null | undefined) {
  if (!date) return '—';
  return new Intl.DateTimeFormat('en', { month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit' }).format(new Date(date));
}

function StatusBadge({ status }: { status: string }) {
  return (
    <span data-testid={`status-${status.toLowerCase()}`} className={`inline-flex items-center gap-1.5 rounded-full border px-2.5 py-1 text-[11px] font-bold uppercase tracking-[0.12em] ${statusStyles[status] ?? statusStyles.OFFLINE}`}>
      <span className={`h-1.5 w-1.5 rounded-full ${statusDot[status] ?? statusDot.OFFLINE}`} />
      {status.toLowerCase()}
    </span>
  );
}

function LoadingRows({ count = 4 }: { count?: number }) {
  return (
    <div className="space-y-3 animate-pulse" data-testid="loading-state">
      {Array.from({ length: count }).map((_, index) => (
        <div key={index} className="h-16 rounded-lg bg-muted/70" />
      ))}
    </div>
  );
}

function ErrorState({ message = 'The control plane is not responding.' }: { message?: string }) {
  return (
    <div className="rounded-xl border border-destructive/25 bg-destructive/5 p-7 text-center" data-testid="error-state">
      <CircleAlert className="mx-auto h-7 w-7 text-destructive" />
      <p className="mt-3 font-semibold text-foreground">Could not load this view</p>
      <p className="mt-1 text-sm text-muted-foreground">{message}</p>
    </div>
  );
}

function EmptyState({ title, detail, action }: { title: string; detail: string; action?: ReactNode }) {
  return (
    <div className="rounded-xl border border-dashed border-border bg-card/60 px-6 py-12 text-center" data-testid="empty-state">
      <div className="mx-auto grid h-12 w-12 place-items-center rounded-2xl bg-secondary text-primary"><Server className="h-5 w-5" /></div>
      <p className="mt-4 font-semibold">{title}</p>
      <p className="mx-auto mt-1 max-w-sm text-sm text-muted-foreground">{detail}</p>
      {action ? <div className="mt-5">{action}</div> : null}
    </div>
  );
}

function SectionHeading({ eyebrow, title, detail, action }: { eyebrow?: string; title: string; detail?: string; action?: ReactNode }) {
  return (
    <div className="mb-6 flex flex-wrap items-end justify-between gap-4">
      <div>
        {eyebrow ? <p className="font-mono text-[10px] font-medium uppercase tracking-[0.22em] text-primary">{eyebrow}</p> : null}
        <h1 className="mt-1 text-2xl font-extrabold tracking-[-0.035em] text-foreground sm:text-3xl">{title}</h1>
        {detail ? <p className="mt-2 max-w-2xl text-sm text-muted-foreground">{detail}</p> : null}
      </div>
      {action}
    </div>
  );
}

function Shell({ children }: { children: ReactNode }) {
  const [location] = useLocation();
  const [mobileNav, setMobileNav] = useState(false);
  const active = location === '/' ? '/' : location.startsWith('/devices') ? '/devices' : '/activity';
  return (
    <div className="min-h-[100dvh] bg-background text-foreground">
      <aside className={`fixed inset-y-0 left-0 z-30 flex w-[248px] flex-col border-r border-sidebar-border bg-sidebar px-4 py-5 transition-transform duration-200 md:translate-x-0 ${mobileNav ? 'translate-x-0' : '-translate-x-full'}`}>
        <div className="flex items-center justify-between px-3">
          <Link href="/" data-testid="link-brand" className="flex items-center gap-3 text-sidebar-foreground">
            <span className="grid h-9 w-9 place-items-center rounded-xl bg-sidebar-primary text-sidebar-primary-foreground shadow-sm"><Terminal className="h-[18px] w-[18px]" /></span>
            <span><span className="block text-[15px] font-extrabold tracking-[-0.04em]">ATPILOT</span><span className="block font-mono text-[9px] uppercase tracking-[0.22em] text-sidebar-foreground/55">device admin</span></span>
          </Link>
          <button onClick={() => setMobileNav(false)} data-testid="button-close-navigation" className="rounded-lg p-2 text-sidebar-foreground/60 hover:bg-sidebar-accent md:hidden"><X className="h-4 w-4" /></button>
        </div>
        <div className="mx-3 mt-10 border-t border-sidebar-border pt-5">
          <p className="px-3 font-mono text-[10px] uppercase tracking-[0.2em] text-sidebar-foreground/40">Control plane</p>
          <nav className="mt-3 space-y-1">
            {navItems.map((item) => {
              const Icon = item.icon;
              return (
                <Link href={item.href} key={item.href} data-testid={`link-nav-${item.label.toLowerCase()}`} className={`group flex items-center gap-3 rounded-lg px-3 py-2.5 text-sm font-semibold ${active === item.href ? 'bg-sidebar-primary text-sidebar-primary-foreground' : 'text-sidebar-foreground/70 hover:bg-sidebar-accent hover:text-sidebar-accent-foreground'}`}>
                  <Icon className="h-[17px] w-[17px]" />
                  {item.label}
                  {item.href === '/devices' ? <span className="ml-auto font-mono text-[10px] opacity-50">fleet</span> : null}
                </Link>
              );
            })}
          </nav>
        </div>
        <div className="mt-auto mx-2 rounded-xl border border-sidebar-border bg-sidebar-accent/60 p-4">
          <div className="flex items-center gap-2 text-sidebar-primary"><span className="h-2 w-2 animate-pulse rounded-full bg-sidebar-primary" /><span className="font-mono text-[10px] font-medium uppercase tracking-[0.16em]">Control plane live</span></div>
          <p className="mt-2 text-xs leading-relaxed text-sidebar-foreground/55">Approved Android endpoints only. Commands are audited.</p>
        </div>
      </aside>
      <div className="md:pl-[248px]">
        <header className="sticky top-0 z-20 flex h-16 items-center justify-between border-b border-border/80 bg-background/90 px-5 backdrop-blur-md sm:px-8">
          <button onClick={() => setMobileNav(true)} data-testid="button-open-navigation" className="rounded-lg p-2 text-muted-foreground hover:bg-secondary md:hidden"><Menu className="h-5 w-5" /></button>
          <div className="hidden items-center gap-2 text-xs text-muted-foreground md:flex"><span className="font-mono text-[10px] uppercase tracking-[0.18em]">Workspace</span><ChevronRight className="h-3.5 w-3.5" /><span className="font-semibold text-foreground">{active === '/' ? 'Fleet overview' : active === '/devices' ? 'Device fleet' : 'Activity log'}</span></div>
          <div className="ml-auto flex items-center gap-3">
            <div className="hidden items-center gap-2 rounded-full border border-border bg-card px-3 py-1.5 text-xs text-muted-foreground sm:flex"><span className="h-1.5 w-1.5 rounded-full bg-teal-500" />Production workspace</div>
            <button data-testid="button-settings" className="rounded-lg border border-border bg-card p-2 text-muted-foreground hover:text-foreground"><Settings2 className="h-4 w-4" /></button>
            <div className="grid h-8 w-8 place-items-center rounded-full bg-foreground text-[11px] font-bold text-background" data-testid="avatar-admin">AK</div>
          </div>
        </header>
        <main className="app-grid min-h-[calc(100dvh-4rem)] px-5 py-7 sm:px-8 sm:py-9">{children}</main>
      </div>
    </div>
  );
}

function StatCard({ label, value, detail, icon: Icon, tone = 'teal' }: { label: string; value: number | string; detail: string; icon: typeof Activity; tone?: 'teal' | 'amber' | 'slate' | 'orange' }) {
  const tones = { teal: 'bg-teal-50 text-teal-700 border-teal-100', amber: 'bg-amber-50 text-amber-700 border-amber-100', slate: 'bg-slate-100 text-slate-700 border-slate-200', orange: 'bg-orange-50 text-orange-700 border-orange-100' };
  return <div className="rounded-xl border border-card-border bg-card p-5 shadow-sm" data-testid={`stat-${label.toLowerCase().replaceAll(' ', '-')}`}><div className="flex items-start justify-between"><span className={`grid h-9 w-9 place-items-center rounded-lg border ${tones[tone]}`}><Icon className="h-4 w-4" /></span><ArrowUpRight className="h-4 w-4 text-muted-foreground/50" /></div><p className="mt-5 font-mono text-3xl font-medium tracking-[-0.08em]">{value}</p><p className="mt-1 text-sm font-semibold">{label}</p><p className="mt-1 text-xs text-muted-foreground">{detail}</p></div>;
}

function ActivityList({ events, compact = false }: { events: ActivityEvent[]; compact?: boolean }) {
  return (
    <div className="divide-y divide-border/70" data-testid="activity-list">
      {events.map((event) => (
        <div key={event.id} className={`flex gap-3 ${compact ? 'py-3' : 'py-4'}`} data-testid={`activity-event-${event.id}`}>
          <div className={`mt-0.5 grid h-7 w-7 shrink-0 place-items-center rounded-lg ${event.type.includes('CONNECTED') || event.type.includes('APPROVED') ? 'bg-teal-100 text-teal-700' : event.type.includes('DISCONNECTED') || event.type.includes('DISABLED') ? 'bg-orange-100 text-orange-700' : 'bg-secondary text-primary'}`}>
            {event.type.includes('COMMAND') ? <Command className="h-3.5 w-3.5" /> : event.type.includes('CONNECTED') ? <Wifi className="h-3.5 w-3.5" /> : event.type.includes('DISCONNECTED') ? <WifiOff className="h-3.5 w-3.5" /> : event.type.includes('APPROVED') ? <ShieldCheck className="h-3.5 w-3.5" /> : <Zap className="h-3.5 w-3.5" />}
          </div>
          <div className="min-w-0 flex-1"><p className="text-sm leading-snug">{event.message}</p><p className="mt-1 font-mono text-[10px] uppercase tracking-[0.08em] text-muted-foreground">{event.deviceName ?? 'Fleet'} <span className="mx-1 text-border">/</span> {formatRelative(event.createdAt)}</p></div>
        </div>
      ))}
    </div>
  );
}

function Overview() {
  const summaryQuery = useGetAdminSummary({ query: { queryKey: getGetAdminSummaryQueryKey() } });
  const activityQuery = useGetActivity({ limit: 6 }, { query: { queryKey: getGetActivityQueryKey({ limit: 6 }) } });
  const devicesQuery = useListDevices(undefined, { query: { queryKey: getListDevicesQueryKey() } });
  const summary = summaryQuery.data;
  const devices = devicesQuery.data ?? [];
  const events = activityQuery.data ?? [];
  const pending = devices.filter((device) => device.status === 'PENDING');
  const onlineRate = summary && summary.totalDevices ? Math.round((summary.onlineDevices / summary.totalDevices) * 100) : 0;
  return (
    <div className="mx-auto max-w-[1420px]">
      <SectionHeading eyebrow="ATPILOT / CONTROL PLANE" title="Fleet overview" detail="A quiet read on the devices running your Android test surface." action={<Link href="/devices" data-testid="link-view-fleet" className="inline-flex items-center gap-2 rounded-lg bg-primary px-4 py-2.5 text-sm font-bold text-primary-foreground shadow-sm hover:opacity-90">View fleet <ArrowUpRight className="h-4 w-4" /></Link>} />
      {summaryQuery.isLoading ? <LoadingRows count={1} /> : summaryQuery.isError ? <ErrorState /> : summary ? <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4"><StatCard label="Total devices" value={summary.totalDevices} detail={`${onlineRate}% responding now`} icon={Smartphone} /><StatCard label="Online now" value={summary.onlineDevices} detail="Ready for commands" icon={Wifi} /><StatCard label="Pending approval" value={summary.pendingDevices} detail="Needs operator review" icon={Clock3} tone="orange" /><StatCard label="Commands today" value={summary.commandsToday} detail="Across the fleet" icon={Command} tone="amber" /></div> : null}
      <div className="mt-6 grid gap-6 xl:grid-cols-[1.2fr_0.8fr]">
        <section className="rounded-xl border border-card-border bg-card p-5 shadow-sm sm:p-6" data-testid="panel-fleet-health">
          <div className="flex items-start justify-between"><div><p className="font-mono text-[10px] uppercase tracking-[0.18em] text-muted-foreground">Readiness</p><h2 className="mt-1 text-lg font-extrabold tracking-[-0.03em]">Fleet health</h2></div><span className="font-mono text-xs text-muted-foreground">{summary?.lastSyncAt ? `synced ${formatRelative(summary.lastSyncAt)}` : 'syncing'}</span></div>
          {devicesQuery.isLoading ? <div className="mt-6"><LoadingRows count={3} /></div> : devicesQuery.isError ? <div className="mt-5"><ErrorState message="Device health is temporarily unavailable." /></div> : devices.length ? <div className="mt-6 space-y-5"><div><div className="mb-2 flex justify-between text-xs"><span className="font-semibold">Online coverage</span><span className="font-mono text-muted-foreground">{onlineRate}%</span></div><div className="h-2 overflow-hidden rounded-full bg-secondary"><div className="h-full rounded-full bg-teal-500 transition-all duration-500" style={{ width: `${onlineRate}%` }} /></div></div><div className="grid gap-3 sm:grid-cols-3"><div className="rounded-lg bg-secondary/60 p-3"><div className="flex items-center gap-2 text-xs font-semibold"><span className="h-2 w-2 rounded-full bg-teal-500" />Online</div><p className="mt-2 font-mono text-xl">{summary?.onlineDevices ?? 0}</p></div><div className="rounded-lg bg-secondary/60 p-3"><div className="flex items-center gap-2 text-xs font-semibold"><span className="h-2 w-2 rounded-full bg-slate-400" />Offline</div><p className="mt-2 font-mono text-xl">{devices.filter((d) => d.status === 'OFFLINE').length}</p></div><div className="rounded-lg bg-secondary/60 p-3"><div className="flex items-center gap-2 text-xs font-semibold"><span className="h-2 w-2 rounded-full bg-amber-500" />Paused</div><p className="mt-2 font-mono text-xl">{summary?.pausedDevices ?? 0}</p></div></div></div> : <EmptyState title="No devices enrolled" detail="Enroll your first Android testing endpoint to start reading fleet health." action={<Link href="/devices" data-testid="link-enroll-first" className="text-sm font-bold text-primary">Open device fleet <ArrowRightIcon /></Link>} />}
        </section>
        <section className="rounded-xl border border-card-border bg-card p-5 shadow-sm sm:p-6" data-testid="panel-recent-events"><div className="flex items-start justify-between"><div><p className="font-mono text-[10px] uppercase tracking-[0.18em] text-muted-foreground">Audit trail</p><h2 className="mt-1 text-lg font-extrabold tracking-[-0.03em]">Recent events</h2></div><Link href="/activity" data-testid="link-see-activity" className="text-xs font-bold text-primary hover:underline">See all</Link></div>{activityQuery.isLoading ? <div className="mt-4"><LoadingRows count={4} /></div> : activityQuery.isError ? <div className="mt-4"><ErrorState message="Recent activity is temporarily unavailable." /></div> : events.length ? <ActivityList events={events} compact /> : <div className="pt-10"><EmptyState title="No activity yet" detail="Operator actions and device changes will appear here." /></div>}</section>
      </div>
      <div className="mt-6 grid gap-6 lg:grid-cols-[1fr_0.42fr]">
        <section className="rounded-xl border border-card-border bg-card p-5 shadow-sm sm:p-6" data-testid="panel-pending-approvals"><div className="flex items-start justify-between"><div><p className="font-mono text-[10px] uppercase tracking-[0.18em] text-muted-foreground">Operator queue</p><h2 className="mt-1 text-lg font-extrabold tracking-[-0.03em]">Pending approvals</h2></div><span className="rounded-full bg-orange-100 px-2.5 py-1 font-mono text-[10px] font-medium text-orange-800">{pending.length} waiting</span></div>{devicesQuery.isLoading ? <div className="mt-4"><LoadingRows count={2} /></div> : pending.length ? <div className="mt-4 grid gap-3 md:grid-cols-2">{pending.slice(0, 4).map((device) => <DeviceMiniCard key={device.id} device={device} />)}</div> : <div className="pt-4"><EmptyState title="Queue is clear" detail="No devices are waiting for approval." /></div>}</section>
        <section className="rounded-xl border border-sidebar-border bg-sidebar p-5 text-sidebar-foreground shadow-sm sm:p-6" data-testid="panel-operations"><div className="flex items-center gap-2 text-sidebar-primary"><Zap className="h-4 w-4" /><p className="font-mono text-[10px] uppercase tracking-[0.18em]">Operations note</p></div><p className="mt-5 text-xl font-extrabold leading-tight tracking-[-0.04em]">Keep the fleet boring.</p><p className="mt-3 text-sm leading-relaxed text-sidebar-foreground/60">Healthy endpoints respond, commands acknowledge, and every exception leaves a trace.</p><Link href="/activity" data-testid="link-open-audit" className="mt-7 inline-flex items-center gap-2 text-xs font-bold text-sidebar-primary hover:underline">Review audit trail <ChevronRight className="h-3.5 w-3.5" /></Link></section>
      </div>
    </div>
  );
}

function ArrowRightIcon() { return <ChevronRight className="ml-1 inline h-3.5 w-3.5" />; }

function DeviceMiniCard({ device }: { device: Device }) {
  return <Link href={`/devices/${device.id}`} data-testid={`card-pending-${device.id}`} className="group flex items-center justify-between rounded-lg border border-border bg-background/60 p-3 hover:border-primary/50 hover:bg-primary/5"><div className="flex min-w-0 items-center gap-3"><span className="grid h-9 w-9 shrink-0 place-items-center rounded-lg bg-orange-100 text-orange-700"><Smartphone className="h-4 w-4" /></span><div className="min-w-0"><p className="truncate text-sm font-bold">{device.displayName}</p><p className="truncate font-mono text-[10px] text-muted-foreground">{device.model} <span className="text-border">/</span> {device.id}</p></div></div><ChevronRight className="h-4 w-4 text-muted-foreground transition-transform group-hover:translate-x-0.5" /></Link>;
}

function EnrollDialog({ onClose }: { onClose: () => void }) {
  const { toast } = useToast();
  const queryClient = useQueryClient();
  const mutation = useEnrollDevice();
  const [form, setForm] = useState({ id: '', displayName: '', model: '', androidVersion: '14', appVersion: '' });
  const update = (key: keyof typeof form) => (event: React.ChangeEvent<HTMLInputElement>) => setForm((current) => ({ ...current, [key]: event.target.value }));
  const submit = (event: React.FormEvent) => {
    event.preventDefault();
    mutation.mutate({ data: form }, {
      onSuccess: (result) => {
        queryClient.invalidateQueries({ queryKey: getListDevicesQueryKey() });
        queryClient.invalidateQueries({ queryKey: getGetAdminSummaryQueryKey() });
        queryClient.invalidateQueries({ queryKey: getGetActivityQueryKey({ limit: 6 }) });
        toast({ title: 'Device enrolled', description: `${result.device.displayName} is ready for approval.` });
        onClose();
      },
      onError: () => toast({ title: 'Enrollment failed', description: 'Check the device details and try again.', variant: 'destructive' }),
    });
  };
  return <div className="fixed inset-0 z-50 grid place-items-center bg-foreground/40 p-4 backdrop-blur-sm" data-testid="dialog-enroll"><div className="w-full max-w-lg rounded-2xl border border-border bg-card p-6 shadow-2xl sm:p-7"><div className="flex items-start justify-between"><div><p className="font-mono text-[10px] uppercase tracking-[0.2em] text-primary">New endpoint</p><h2 className="mt-1 text-xl font-extrabold tracking-[-0.04em]">Enroll Android device</h2><p className="mt-2 text-sm text-muted-foreground">Add a device to the approval queue. It will not receive commands until approved.</p></div><button onClick={onClose} data-testid="button-close-enroll" className="rounded-lg p-2 text-muted-foreground hover:bg-secondary"><X className="h-4 w-4" /></button></div><form onSubmit={submit} className="mt-6 space-y-4">{[['id', 'Device ID', 'atpilot-android-01'], ['displayName', 'Display name', 'Lab Pixel 8'], ['model', 'Model', 'Pixel 8'], ['androidVersion', 'Android version', '14'], ['appVersion', 'ATPILOT app version', '2.4.1']].map(([key, label, placeholder]) => <label key={key} className="block"><span className="mb-1.5 block text-xs font-bold">{label}</span><input required value={form[key as keyof typeof form]} onChange={update(key as keyof typeof form)} placeholder={placeholder} data-testid={`input-enroll-${key}`} className="h-10 w-full rounded-lg border border-input bg-background px-3 text-sm outline-none placeholder:text-muted-foreground/50 focus:border-primary focus:ring-2 focus:ring-primary/15" /></label>)}<div className="flex justify-end gap-2 pt-3"><button type="button" onClick={onClose} data-testid="button-cancel-enroll" className="rounded-lg px-4 py-2.5 text-sm font-bold text-muted-foreground hover:bg-secondary">Cancel</button><button type="submit" disabled={mutation.isPending} data-testid="button-submit-enroll" className="inline-flex items-center gap-2 rounded-lg bg-primary px-4 py-2.5 text-sm font-bold text-primary-foreground disabled:opacity-60">{mutation.isPending ? <RefreshCw className="h-4 w-4 animate-spin" /> : <Plus className="h-4 w-4" />} {mutation.isPending ? 'Enrolling…' : 'Enroll device'}</button></div>{mutation.isError ? <p className="text-right text-xs font-semibold text-destructive">Enrollment could not be completed.</p> : null}</form></div></div>;
}

function DevicesPage() {
  const [search, setSearch] = useState('');
  const [status, setStatus] = useState('ALL');
  const [showEnroll, setShowEnroll] = useState(false);
  const params = useMemo<ListDevicesParams>(() => ({ search: search || undefined, status: status === 'ALL' ? undefined : status as ListDevicesParams['status'] }), [search, status]);
  const query = useListDevices(params, { query: { queryKey: getListDevicesQueryKey(params) } });
  const devices = query.data ?? [];
  return <div className="mx-auto max-w-[1420px]"><SectionHeading eyebrow="FLEET / ENDPOINTS" title="Device fleet" detail="Approved Android endpoints, their current posture, and the last time they checked in." action={<button onClick={() => setShowEnroll(true)} data-testid="button-open-enroll" className="inline-flex items-center gap-2 rounded-lg bg-primary px-4 py-2.5 text-sm font-bold text-primary-foreground shadow-sm hover:opacity-90"><Plus className="h-4 w-4" /> Enroll device</button>} /><div className="rounded-xl border border-card-border bg-card shadow-sm"><div className="flex flex-col gap-3 border-b border-border p-4 sm:flex-row sm:items-center sm:justify-between"><label className="relative block flex-1 sm:max-w-sm"><Search className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" /><input value={search} onChange={(event) => setSearch(event.target.value)} data-testid="input-device-search" placeholder="Search by name, model, or ID" className="h-10 w-full rounded-lg border border-input bg-background pl-9 pr-3 text-sm outline-none focus:border-primary focus:ring-2 focus:ring-primary/15" /></label><div className="flex items-center gap-2"><span className="font-mono text-[10px] uppercase tracking-[0.14em] text-muted-foreground">Status</span><select value={status} onChange={(event) => setStatus(event.target.value)} data-testid="select-device-status" className="h-10 rounded-lg border border-input bg-background px-3 text-sm font-semibold outline-none focus:border-primary"><option value="ALL">All devices</option><option value="ONLINE">Online</option><option value="OFFLINE">Offline</option><option value="PAUSED">Paused</option><option value="PENDING">Pending</option></select></div></div>{query.isLoading ? <div className="p-5"><LoadingRows count={5} /></div> : query.isError ? <div className="p-5"><ErrorState message="The device directory could not be reached." /></div> : devices.length ? <DeviceTable devices={devices} /> : <div className="p-5"><EmptyState title={search || status !== 'ALL' ? 'No matching devices' : 'No devices enrolled'} detail={search || status !== 'ALL' ? 'Try a different search or clear the status filter.' : 'Enroll an Android testing endpoint to build your fleet.'} action={<button onClick={() => setShowEnroll(true)} data-testid="button-empty-enroll" className="inline-flex items-center gap-2 rounded-lg bg-primary px-4 py-2 text-sm font-bold text-primary-foreground"><Plus className="h-4 w-4" /> Enroll device</button>} /></div>}</div>{showEnroll ? <EnrollDialog onClose={() => setShowEnroll(false)} /> : null}</div>;
}

function DeviceTable({ devices }: { devices: Device[] }) {
  return <div className="overflow-x-auto"><table className="w-full min-w-[720px] text-left"><thead><tr className="border-b border-border bg-secondary/35 font-mono text-[10px] uppercase tracking-[0.15em] text-muted-foreground"><th className="px-5 py-3 font-medium">Device</th><th className="px-4 py-3 font-medium">Status</th><th className="px-4 py-3 font-medium">Battery</th><th className="px-4 py-3 font-medium">Software</th><th className="px-4 py-3 font-medium">Last seen</th><th className="px-5 py-3" /></tr></thead><tbody className="divide-y divide-border/70">{devices.map((device) => <tr key={device.id} className="group hover:bg-primary/[0.035]" data-testid={`row-device-${device.id}`}><td className="px-5 py-4"><Link href={`/devices/${device.id}`} data-testid={`link-device-${device.id}`} className="flex items-center gap-3"><span className={`grid h-9 w-9 place-items-center rounded-lg ${device.status === 'ONLINE' ? 'bg-teal-100 text-teal-700' : 'bg-secondary text-muted-foreground'}`}><Smartphone className="h-4 w-4" /></span><span><span className="block text-sm font-bold group-hover:text-primary">{device.displayName}</span><span className="mt-0.5 block font-mono text-[10px] text-muted-foreground">{device.model} <span className="text-border">/</span> {device.id}</span></span></Link></td><td className="px-4 py-4"><StatusBadge status={device.status} /></td><td className="px-4 py-4"><div className="flex items-center gap-2 text-sm font-semibold">{device.batteryLevel == null ? '—' : <><BatteryCharging className={`h-4 w-4 ${device.batteryLevel < 20 ? 'text-orange-600' : 'text-teal-600'}`} />{device.batteryLevel}%</>}</div></td><td className="px-4 py-4"><p className="font-mono text-xs">{device.androidVersion}</p><p className="mt-0.5 text-[11px] text-muted-foreground">app {device.appVersion}</p></td><td className="px-4 py-4 font-mono text-[11px] text-muted-foreground">{formatRelative(device.lastSeenAt)}</td><td className="px-5 py-4 text-right"><Link href={`/devices/${device.id}`} data-testid={`link-open-device-${device.id}`} className="inline-flex rounded-lg p-2 text-muted-foreground hover:bg-secondary hover:text-foreground"><ChevronRight className="h-4 w-4" /></Link></td></tr>)}</tbody></table></div>;
}

function CommandButton({ deviceId, type, action = 'NONE', label, icon: Icon, tone = 'default' }: { deviceId: string; type: DeviceCommandInput['type']; action?: DeviceCommandInput['action']; label: string; icon: typeof Play; tone?: 'default' | 'warning' }) {
  const { toast } = useToast();
  const queryClient = useQueryClient();
  const mutation = useSendDeviceCommand();
  return <button onClick={() => mutation.mutate({ deviceId, data: { type, action, templateName: null } }, { onSuccess: () => { queryClient.invalidateQueries({ queryKey: getListDeviceCommandsQueryKey(deviceId) }); queryClient.invalidateQueries({ queryKey: getGetActivityQueryKey({ limit: 6 }) }); toast({ title: 'Command queued', description: `${label} sent to the device queue.` }); }, onError: () => toast({ title: 'Command failed', description: 'The device did not accept this command.', variant: 'destructive' }) })} disabled={mutation.isPending} data-testid={`button-command-${type.toLowerCase()}-${deviceId}`} className={`inline-flex items-center justify-center gap-2 rounded-lg border px-3 py-2.5 text-xs font-bold disabled:opacity-60 ${tone === 'warning' ? 'border-amber-200 bg-amber-50 text-amber-800 hover:bg-amber-100' : 'border-border bg-card text-foreground hover:border-primary/50 hover:bg-primary/5'}`}><Icon className={`h-3.5 w-3.5 ${mutation.isPending ? 'animate-spin' : ''}`} />{mutation.isPending ? 'Sending…' : label}</button>;
}

function DeviceDetailPage() {
  const { deviceId = '' } = useParams<{ deviceId: string }>();
  const { toast } = useToast();
  const queryClient = useQueryClient();
  const deviceQuery = useGetDevice(deviceId, { query: { queryKey: getGetDeviceQueryKey(deviceId) } });
  const commandsQuery = useListDeviceCommands(deviceId, { query: { queryKey: getListDeviceCommandsQueryKey(deviceId) } });
  const updateMutation = useUpdateDevice();
  const device = deviceQuery.data;
  const commands = commandsQuery.data ?? [];
  const togglePause = () => {
    if (!device) return;
    updateMutation.mutate({ deviceId, data: { paused: device.status !== 'PAUSED' } }, { onSuccess: () => { queryClient.invalidateQueries({ queryKey: getGetDeviceQueryKey(deviceId) }); queryClient.invalidateQueries({ queryKey: getListDevicesQueryKey() }); toast({ title: device.status === 'PAUSED' ? 'Device resumed' : 'Device paused', description: `${device.displayName} status updated.` }); }, onError: () => toast({ title: 'Update failed', description: 'The device status could not be changed.', variant: 'destructive' }) });
  };
  const approve = () => {
    updateMutation.mutate({ deviceId, data: { approved: true } }, { onSuccess: () => { queryClient.invalidateQueries({ queryKey: getGetDeviceQueryKey(deviceId) }); queryClient.invalidateQueries({ queryKey: getListDevicesQueryKey() }); queryClient.invalidateQueries({ queryKey: getGetAdminSummaryQueryKey() }); toast({ title: 'Device approved', description: 'The endpoint can now receive commands.' }); }, onError: () => toast({ title: 'Approval failed', description: 'Try again when the control plane is available.', variant: 'destructive' }) });
  };
  if (deviceQuery.isLoading) return <div className="mx-auto max-w-[1100px]"><LoadingRows count={4} /></div>;
  if (deviceQuery.isError || !device) return <div className="mx-auto max-w-[700px]"><ErrorState message="This device could not be found or is temporarily unavailable." /><Link href="/devices" data-testid="link-back-fleet-error" className="mt-4 inline-flex items-center gap-2 text-sm font-bold text-primary"><ArrowLeft className="h-4 w-4" /> Back to fleet</Link></div>;
  return <div className="mx-auto max-w-[1100px]"><Link href="/devices" data-testid="link-back-fleet" className="mb-6 inline-flex items-center gap-2 text-xs font-bold text-muted-foreground hover:text-foreground"><ArrowLeft className="h-3.5 w-3.5" /> Device fleet</Link><div className="flex flex-wrap items-start justify-between gap-4"><div className="flex items-start gap-4"><span className={`grid h-14 w-14 place-items-center rounded-2xl ${device.status === 'ONLINE' ? 'bg-teal-100 text-teal-700' : 'bg-secondary text-muted-foreground'}`}><Smartphone className="h-6 w-6" /></span><div><div className="flex flex-wrap items-center gap-3"><h1 className="text-2xl font-extrabold tracking-[-0.04em] sm:text-3xl" data-testid="text-device-name">{device.displayName}</h1><StatusBadge status={device.status} /></div><p className="mt-1 font-mono text-xs text-muted-foreground">{device.id} <span className="mx-1 text-border">/</span> enrolled {formatDate(device.enrolledAt)}</p></div></div><div className="flex flex-wrap gap-2">{device.status === 'PENDING' && <button onClick={approve} disabled={updateMutation.isPending} data-testid="button-approve-device" className="inline-flex items-center gap-2 rounded-lg bg-primary px-3 py-2.5 text-xs font-bold text-primary-foreground disabled:opacity-60"><ShieldCheck className="h-3.5 w-3.5" /> Approve device</button>}{device.status !== 'PENDING' && <button onClick={togglePause} disabled={updateMutation.isPending} data-testid="button-toggle-pause" className="inline-flex items-center gap-2 rounded-lg border border-border bg-card px-3 py-2.5 text-xs font-bold hover:bg-secondary disabled:opacity-60">{device.status === 'PAUSED' ? <Play className="h-3.5 w-3.5" /> : <Pause className="h-3.5 w-3.5" />}{device.status === 'PAUSED' ? 'Resume device' : 'Pause device'}</button>}</div></div><div className="mt-7 grid gap-4 sm:grid-cols-3"><InfoTile label="Battery" value={device.batteryLevel == null ? 'Unknown' : `${device.batteryLevel}%`} icon={BatteryCharging} detail={device.batteryLevel != null && device.batteryLevel < 20 ? 'Low charge' : 'Within operating range'} /><InfoTile label="Last seen" value={formatRelative(device.lastSeenAt)} icon={Wifi} detail={device.lastSeenAt ? formatDate(device.lastSeenAt) : 'No heartbeat recorded'} /><InfoTile label="Software" value={`Android ${device.androidVersion}`} icon={Terminal} detail={`ATPILOT app ${device.appVersion}`} /></div><div className="mt-6 grid gap-6 lg:grid-cols-[0.8fr_1.2fr]"><section className="rounded-xl border border-card-border bg-card p-5 shadow-sm sm:p-6" data-testid="panel-safe-controls"><div><p className="font-mono text-[10px] uppercase tracking-[0.18em] text-primary">Safe controls</p><h2 className="mt-1 text-lg font-extrabold tracking-[-0.03em]">Operator commands</h2><p className="mt-2 text-sm text-muted-foreground">Actions enter the device queue and are recorded in the audit trail.</p></div><div className="mt-5 grid grid-cols-2 gap-2"><CommandButton deviceId={deviceId} type="START" label="Start run" icon={Play} /><CommandButton deviceId={deviceId} type="STOP" label="Stop run" icon={X} tone="warning" /><CommandButton deviceId={deviceId} type="REFRESH_STATUS" label="Refresh status" icon={RefreshCw} /><CommandButton deviceId={deviceId} type="PAUSE" label="Pause queue" icon={Pause} /></div><div className="mt-5 border-t border-border pt-4"><p className="mb-3 font-mono text-[10px] uppercase tracking-[0.16em] text-muted-foreground">Navigation actions</p><div className="grid grid-cols-3 gap-2"><CommandButton deviceId={deviceId} type="START" action="BACK" label="Back" icon={ArrowLeft} /><CommandButton deviceId={deviceId} type="START" action="HOME" label="Home" icon={Home} /><CommandButton deviceId={deviceId} type="START" action="NOTIFICATIONS" label="Alerts" icon={Bell} /></div></div></section><section className="rounded-xl border border-card-border bg-card p-5 shadow-sm sm:p-6" data-testid="panel-device-commands"><div className="flex items-start justify-between"><div><p className="font-mono text-[10px] uppercase tracking-[0.18em] text-muted-foreground">Command history</p><h2 className="mt-1 text-lg font-extrabold tracking-[-0.03em]">Recent commands</h2></div><span className="font-mono text-[10px] uppercase text-muted-foreground">{commands.length} records</span></div>{commandsQuery.isLoading ? <div className="mt-5"><LoadingRows count={4} /></div> : commandsQuery.isError ? <div className="mt-5"><ErrorState message="Command history is temporarily unavailable." /></div> : commands.length ? <CommandTable commands={commands} /> : <div className="pt-8"><EmptyState title="No commands yet" detail="Safe controls will appear here after the first operator action." /></div>}</section></div></div>;
}

function InfoTile({ label, value, detail, icon: Icon }: { label: string; value: string; detail: string; icon: typeof Wifi }) {
  return <div className="rounded-xl border border-card-border bg-card p-4 shadow-sm" data-testid={`tile-${label.toLowerCase()}`}><div className="flex items-center gap-2 text-muted-foreground"><Icon className="h-4 w-4" /><span className="font-mono text-[10px] uppercase tracking-[0.16em]">{label}</span></div><p className="mt-4 text-lg font-extrabold tracking-[-0.035em]">{value}</p><p className="mt-1 truncate text-xs text-muted-foreground">{detail}</p></div>;
}

function CommandTable({ commands }: { commands: DeviceCommand[] }) {
  return <div className="mt-4 divide-y divide-border/70" data-testid="command-list">{commands.map((command) => <div key={command.id} className="flex items-center justify-between gap-3 py-3" data-testid={`command-${command.id}`}><div className="flex min-w-0 items-center gap-3"><div className="grid h-8 w-8 shrink-0 place-items-center rounded-lg bg-secondary text-primary"><Command className="h-3.5 w-3.5" /></div><div className="min-w-0"><p className="text-sm font-bold">{command.type.replace('_', ' ')}{command.action !== 'NONE' ? <span className="ml-2 font-mono text-[10px] font-medium text-primary">/ {command.action.toLowerCase()}</span> : null}</p><p className="font-mono text-[10px] text-muted-foreground">{formatDate(command.createdAt)} {command.templateName ? `· ${command.templateName}` : ''}</p></div></div><span className={`shrink-0 rounded-full px-2 py-1 font-mono text-[9px] font-medium uppercase tracking-[0.1em] ${command.status === 'ACKNOWLEDGED' ? 'bg-teal-100 text-teal-800' : command.status === 'FAILED' || command.status === 'EXPIRED' ? 'bg-orange-100 text-orange-800' : 'bg-secondary text-muted-foreground'}`}>{command.status.toLowerCase()}</span></div>)}</div>;
}

function ActivityPage() {
  const query = useGetActivity({ limit: 50 }, { query: { queryKey: getGetActivityQueryKey({ limit: 50 }) } });
  const events = query.data ?? [];
  return <div className="mx-auto max-w-[980px]"><SectionHeading eyebrow="AUDIT / TIMELINE" title="Activity log" detail="Every enrollment, approval, command, and connection change in one operator-readable trail." action={<div className="flex items-center gap-2 rounded-lg border border-border bg-card px-3 py-2 text-xs font-semibold text-muted-foreground"><Activity className="h-3.5 w-3.5 text-primary" /> Last 50 events</div>} /><section className="rounded-xl border border-card-border bg-card p-5 shadow-sm sm:p-7" data-testid="panel-activity-log">{query.isLoading ? <LoadingRows count={8} /> : query.isError ? <ErrorState message="The activity log could not be loaded." /> : events.length ? <ActivityList events={events} /> : <EmptyState title="No events recorded" detail="The audit trail is ready. Device changes will appear as they happen." />}</section></div>;
}

function Router() {
  const [location] = useLocation();
  return <Shell><ErrorBoundary resetKey={location}><Switch><Route path="/" component={Overview} /><Route path="/devices" component={DevicesPage} /><Route path="/devices/:deviceId" component={DeviceDetailPage} /><Route path="/activity" component={ActivityPage} /><Route component={NotFound} /></Switch></ErrorBoundary></Shell>;
}

function App() {
  return <QueryClientProvider client={queryClient}><TooltipProvider><WouterRouter base={import.meta.env.BASE_URL.replace(/\/$/, '')}><Router /></WouterRouter><Toaster /></TooltipProvider></QueryClientProvider>;
}

export default App;