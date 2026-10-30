import { Injectable, signal } from '@angular/core';

const STORAGE_KEY = 'biat_theme';

/** Bascule clair/sombre appliquée en ajoutant/retirant la classe "dark-theme" sur <html> (voir
 * styles.scss, bloc ".dark-theme" qui redéfinit les variables --biat-* et re-thème Angular
 * Material) — persistée dans localStorage pour rester active d'une navigation/session à l'autre. */
@Injectable({ providedIn: 'root' })
export class ThemeService {
  private readonly darkSignal = signal(this.readInitialPreference());

  readonly isDark = this.darkSignal.asReadonly();

  constructor() {
    this.applyToDocument(this.darkSignal());
  }

  toggle(): void {
    const next = !this.darkSignal();
    this.darkSignal.set(next);
    this.applyToDocument(next);
    try {
      localStorage.setItem(STORAGE_KEY, next ? 'dark' : 'light');
    } catch {
      // Stockage indisponible (navigation privée...) : la préférence reste active pour la session
      // en cours via le signal, seule la persistance entre sessions est perdue.
    }
  }

  private readInitialPreference(): boolean {
    try {
      const stored = localStorage.getItem(STORAGE_KEY);
      if (stored === 'dark') return true;
      if (stored === 'light') return false;
    } catch {
      // ignore, voir toggle()
    }
    return typeof window !== 'undefined' && window.matchMedia?.('(prefers-color-scheme: dark)').matches;
  }

  private applyToDocument(dark: boolean): void {
    document.documentElement.classList.toggle('dark-theme', dark);
  }
}
