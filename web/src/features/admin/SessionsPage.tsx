// /admin/sessions ("Reproducciones"): the HLS sessions the server runs, polled every 3 s while
// mounted, with "Detener" per session (table on desktop, cards on phones).
import { useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { stopSession } from '@/api/admin';
import { errorMessage, isApiError } from '@/api/errors';
import { runInvalidation } from '@/api/queryClient';
import type { AdminSession, AdminSessions } from '@/api/types';
import { isAdminActionCancelled, useAdminAction } from '@/auth/useAdminAction';
import { ErrorState } from '@/components/ErrorState';
import { Skeleton } from '@/components/Skeleton';
import { useToast } from '@/components/Toast';
import { formatClockTime, formatDecimal } from '@/lib/format';
import { PHONE_QUERY, useMediaQuery } from '@/lib/useMediaQuery';
import { S } from '@/strings.es';
import adminStyles from './Admin.module.css';
import { useSessionsQuery } from './adminQueries';
import {
  audioLabel,
  lastSegmentText,
  profileLabel,
  sessionStateLabel,
  sessionTitle,
  speedText,
  stopLabel,
  videoLabel,
} from './sessionRows';
import styles from './Sessions.module.css';

const T = S.admin.sessions;

export function SessionsPage() {
  const sessions = useSessionsQuery();
  const phone = useMediaQuery(PHONE_QUERY);
  const queryClient = useQueryClient();
  const runAdmin = useAdminAction();
  const toast = useToast();
  const [stopping, setStopping] = useState<ReadonlySet<string>>(new Set());

  const onStop = async (id: string) => {
    setStopping((current) => new Set(current).add(id));
    try {
      await runAdmin(() => stopSession(id), T.stopPurpose);
      void runInvalidation(queryClient, { type: 'sessionStopped' });
      toast.show(T.stopped);
    } catch (err) {
      if (isAdminActionCancelled(err)) return;
      if (isApiError(err, 'session_not_found')) {
        void runInvalidation(queryClient, { type: 'sessionStopped' });
        toast.show(T.stopped);
      } else {
        toast.show(errorMessage(err));
      }
    } finally {
      setStopping((current) => {
        const next = new Set(current);
        next.delete(id);
        return next;
      });
    }
  };

  if (sessions.data === undefined) {
    return (
      <div className={adminStyles.panel}>
        {sessions.isError ? (
          <ErrorState message={errorMessage(sessions.error)} onRetry={() => void sessions.refetch()} />
        ) : (
          <div role="progressbar" aria-label={S.common.loading} className={adminStyles.cardSkeleton}>
            <Skeleton className={adminStyles.skeletonLine} />
            <Skeleton className={adminStyles.skeletonLine} />
          </div>
        )}
      </div>
    );
  }

  const data = sessions.data;
  return (
    <div className={adminStyles.panel}>
      <Summary data={data} />
      {data.sessions.length === 0 ? (
        <p className={styles.empty}>{T.empty}</p>
      ) : phone ? (
        <ul className={styles.cards} aria-label={T.heading}>
          {data.sessions.map((s) => (
            <SessionCard key={s.session_id} session={s} stopping={stopping.has(s.session_id)} onStop={() => void onStop(s.session_id)} />
          ))}
        </ul>
      ) : (
        <SessionTable sessions={data.sessions} stopping={stopping} onStop={(id) => void onStop(id)} />
      )}
    </div>
  );
}

function Summary({ data }: { data: AdminSessions }) {
  return (
    <div className={styles.summary}>
      <p>{T.summary(data.active_sessions, data.limits.max_sessions, data.active_transcodes, data.limits.max_transcodes)}</p>
      <p>{data.cpu ? T.cpu(formatDecimal(data.cpu.load_1m, 1), data.cpu.cores) : T.cpuUnavailable}</p>
    </div>
  );
}

interface RowProps {
  session: AdminSession;
  stopping: boolean;
  onStop: () => void;
}

function StopButton({ session, stopping, onStop }: RowProps) {
  return (
    <button type="button" className="button" disabled={stopping} aria-label={stopLabel(session)} onClick={onStop}>
      {T.stop}
    </button>
  );
}

interface TableProps {
  sessions: AdminSession[];
  stopping: ReadonlySet<string>;
  onStop: (id: string) => void;
}

function SessionTable({ sessions, stopping, onStop }: TableProps) {
  const c = T.columns;
  return (
    <div className={styles.tableWrap}>
      <table className={styles.table}>
        <caption className="visually-hidden">{T.heading}</caption>
        <thead>
          <tr>
            {[c.profile, c.title, c.file, c.video, c.audio, c.state, c.started, c.lastSegment, c.speed].map((label) => (
              <th key={label} scope="col">
                {label}
              </th>
            ))}
            <td />
          </tr>
        </thead>
        <tbody>
          {sessions.map((s) => (
            <tr key={s.session_id}>
              <td>{profileLabel(s)}</td>
              <td>{sessionTitle(s)}</td>
              <td className={styles.file}>
                <span title={s.file_name}>{s.file_name}</span>
              </td>
              <td>{videoLabel(s.video_action)}</td>
              <td>{audioLabel(s.audio_action)}</td>
              <td>{sessionStateLabel(s)}</td>
              <td>{formatClockTime(s.started_at)}</td>
              <td>{lastSegmentText(s)}</td>
              <td>{speedText(s.speed)}</td>
              <td>
                <StopButton session={s} stopping={stopping.has(s.session_id)} onStop={() => onStop(s.session_id)} />
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function SessionCard({ session: s, stopping, onStop }: RowProps) {
  return (
    <li className={styles.card}>
      <p className={styles.cardTitle}>{sessionTitle(s)}</p>
      <p>{`${profileLabel(s)} · ${sessionStateLabel(s)} · ${speedText(s.speed)}`}</p>
      <p>{T.videoLine(videoLabel(s.video_action))}</p>
      <p>{T.audioLine(audioLabel(s.audio_action))}</p>
      <p>{`${formatClockTime(s.started_at)} · ${lastSegmentText(s)}`}</p>
      <div className={styles.cardActions}>
        <StopButton session={s} stopping={stopping} onStop={onStop} />
      </div>
    </li>
  );
}
