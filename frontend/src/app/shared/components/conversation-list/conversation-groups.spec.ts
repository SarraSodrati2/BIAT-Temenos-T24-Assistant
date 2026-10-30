import { Chat } from '../../../core/models/chat-session.model';
import { formatWhen, groupByRecency } from './conversation-groups';

function chat(id: number, updatedAt: Date): Chat {
  return { id, title: 't' + id, createdAt: updatedAt.toISOString(), updatedAt: updatedAt.toISOString(), userId: 1, messageCount: 2 };
}

describe('groupByRecency', () => {
  const now = new Date(2026, 8, 24, 15, 0, 0);
  const daysAgo = (n: number) => new Date(2026, 8, 24 - n, 10, 0, 0);

  it('range les conversations par ancienneté et omet les groupes vides', () => {
    const groups = groupByRecency([chat(1, daysAgo(0)), chat(2, daysAgo(1)), chat(3, daysAgo(4)), chat(4, daysAgo(30))], now);
    expect(groups.map(g => g.label)).toEqual(["Aujourd'hui", 'Hier', '7 derniers jours', 'Plus ancien']);
    expect(groups.every(g => g.chats.length === 1)).toBeTrue();
  });

  it('ne renvoie aucun groupe pour une liste vide', () => {
    expect(groupByRecency([], now)).toEqual([]);
  });

  it("conserve l'ordre reçu dans un groupe", () => {
    const groups = groupByRecency([chat(2, daysAgo(0)), chat(1, daysAgo(0))], now);
    expect(groups[0].chats.map(c => c.id)).toEqual([2, 1]);
  });
});

describe('formatWhen', () => {
  it("affiche l'heure pour aujourd'hui et la date sinon", () => {
    const now = new Date(2026, 8, 24, 15, 0, 0);
    expect(formatWhen(new Date(2026, 8, 24, 9, 5).toISOString(), now)).toMatch(/^09[:h]05$/);
    expect(formatWhen(new Date(2026, 8, 20, 9, 5).toISOString(), now)).toBe('20/09/2026');
  });
});
