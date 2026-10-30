import { Injectable, signal } from '@angular/core';

/** En dessous de cette largeur, la navigation latérale devient un tiroir (voir ShellComponent). */
export const DESKTOP_BREAKPOINT = 1024;

@Injectable({ providedIn: 'root' })
export class LayoutService {
  private readonly isMobileSignal = signal(this.computeIsMobile());

  /** true sur téléphone/tablette : la navigation latérale est alors un tiroir, pas une colonne fixe. */
  readonly isMobile = this.isMobileSignal.asReadonly();

  // Démarre replié sur téléphone/tablette pour que le tiroir ne recouvre pas l'écran au chargement.
  private readonly sidebarCollapsedSignal = signal(this.computeIsMobile());

  readonly sidebarCollapsed = this.sidebarCollapsedSignal.asReadonly();

  constructor() {
    if (typeof window !== 'undefined') {
      window.addEventListener('resize', () => {
        const mobile = this.computeIsMobile();
        if (mobile !== this.isMobileSignal()) {
          this.isMobileSignal.set(mobile);
          // Changement de mode : tiroir fermé sur mobile, colonne dépliée sur ordinateur.
          this.sidebarCollapsedSignal.set(mobile);
        }
      });
    }
  }

  toggleSidebar(): void {
    this.sidebarCollapsedSignal.update(v => !v);
  }

  /** Ouvre le tiroir de navigation (mobile uniquement) — il contient la liste des conversations. */
  openDrawer(): void {
    if (this.isMobileSignal()) {
      this.sidebarCollapsedSignal.set(false);
    }
  }

  /** Referme le tiroir de navigation (mobile uniquement) — appelé après une navigation. */
  closeDrawer(): void {
    if (this.isMobileSignal()) {
      this.sidebarCollapsedSignal.set(true);
    }
  }

  private computeIsMobile(): boolean {
    return typeof window !== 'undefined' && window.innerWidth < DESKTOP_BREAKPOINT;
  }
}
