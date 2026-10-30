import { Chat } from '../../../core/models/chat-session.model';

export interface ConversationGroup {
  label: string;
  chats: Chat[];
}

const DAY_MS = 24 * 60 * 60 * 1000;

function startOfDay(d: Date): number {
  return new Date(d.getFullYear(), d.getMonth(), d.getDate()).getTime();
}

/**
 * Regroupe les conversations par ancienneté de leur dernière activité (Aujourd'hui / Hier /
 * 7 derniers jours / Plus ancien) en conservant l'ordre reçu à l'intérieur de chaque groupe. Les
 * groupes vides sont omis.
 */
export function groupByRecency(chats: Chat[], now: Date = new Date()): ConversationGroup[] {
  const today = startOfDay(now);
  const buckets: ConversationGroup[] = [
    { label: "Aujourd'hui", chats: [] },
    { label: 'Hier', chats: [] },
    { label: '7 derniers jours', chats: [] },
    { label: 'Plus ancien', chats: [] },
  ];

  for (const chat of chats) {
    const day = startOfDay(new Date(chat.updatedAt));
    if (day >= today) {
      buckets[0].chats.push(chat);
    } else if (day >= today - DAY_MS) {
      buckets[1].chats.push(chat);
    } else if (day >= today - 7 * DAY_MS) {
      buckets[2].chats.push(chat);
    } else {
      buckets[3].chats.push(chat);
    }
  }
  return buckets.filter(b => b.chats.length > 0);
}

/** Heure pour aujourd'hui, sinon date courte (« 18/09/2026 ») — pour la ligne de méta d'une conversation. */
export function formatWhen(iso: string, now: Date = new Date()): string {
  const date = new Date(iso);
  if (startOfDay(date) === startOfDay(now)) {
    return date.toLocaleTimeString('fr-FR', { hour: '2-digit', minute: '2-digit' });
  }
  return date.toLocaleDateString('fr-FR', { day: '2-digit', month: '2-digit', year: 'numeric' });
}
