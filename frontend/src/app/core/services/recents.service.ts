import { Injectable, computed, signal } from '@angular/core';
import { AuthService } from './auth.service';

export type RecentKind = 'module' | 'table';

export interface RecentEntry {
  kind: RecentKind;
  name: string;
  /** Horodatage (ms) de la dernière consultation. */
  at: number;
}

const STORAGE_PREFIX = 'biat_recents:';
const MAX_ENTRIES = 12;

/**
 * Modules et tables réellement consultés par l'utilisateur sur CE navigateur (aucune donnée
 * d'exemple) : alimentent l'accueil « Consultés récemment » et évitent de re-chercher ce qu'on
 * ouvre souvent. Stockés dans localStorage, par utilisateur (poste partagé) ; si le stockage est
 * indisponible, la liste vit simplement en mémoire pour la session.
 */
@Injectable({ providedIn: 'root' })
export class RecentsService {
  private readonly entriesSignal = signal<RecentEntry[]>([]);

  readonly entries = this.entriesSignal.asReadonly();
  readonly modules = computed(() => this.entriesSignal().filter(e => e.kind === 'module'));
  readonly tables = computed(() => this.entriesSignal().filter(e => e.kind === 'table'));

  constructor(private readonly auth: AuthService) {
    // Lecture dans le constructeur (et non dans l'initialiseur du champ) : `auth` n'est assigné qu'ici.
    this.entriesSignal.set(this.read());
  }

  record(kind: RecentKind, name: string): void {
    if (!name) {
      return;
    }
    const next = [
      { kind, name, at: Date.now() },
      ...this.entriesSignal().filter(e => !(e.kind === kind && e.name === name)),
    ].slice(0, MAX_ENTRIES);
    this.entriesSignal.set(next);
    this.write(next);
  }

  clear(): void {
    this.entriesSignal.set([]);
    this.write([]);
  }

  /** À rappeler après connexion/déconnexion : la liste dépend de l'utilisateur courant. */
  reload(): void {
    this.entriesSignal.set(this.read());
  }

  private key(): string {
    return STORAGE_PREFIX + (this.auth.username() ?? 'anonyme');
  }

  private read(): RecentEntry[] {
    try {
      const raw = localStorage.getItem(this.key());
      const parsed = raw ? JSON.parse(raw) : [];
      return Array.isArray(parsed)
        ? parsed.filter((e): e is RecentEntry => !!e && typeof e.name === 'string' && (e.kind === 'module' || e.kind === 'table'))
        : [];
    } catch {
      return [];
    }
  }

  private write(entries: RecentEntry[]): void {
    try {
      localStorage.setItem(this.key(), JSON.stringify(entries));
    } catch {
      // Stockage indisponible : la liste reste valable pour la session en cours (signal).
    }
  }
}
