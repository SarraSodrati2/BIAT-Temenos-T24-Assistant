import { Component, computed } from '@angular/core';
import { RouterLink, RouterLinkActive } from '@angular/router';
import { MatIconModule } from '@angular/material/icon';
import { AuthService } from '../../core/services/auth.service';
import { LayoutService } from '../../core/services/layout.service';
import { sectionsForRole } from '../nav-items';

/** Onglets du bas (téléphone) : les destinations les plus fréquentes + « Menu » qui ouvre le tiroir complet. */
@Component({
  selector: 'app-bottom-nav',
  standalone: true,
  imports: [RouterLink, RouterLinkActive, MatIconModule],
  template: `
    <nav class="bnav" aria-label="Navigation rapide">
      @for (item of items(); track item.route) {
        <a class="bnav__item" [routerLink]="item.route" routerLinkActive="bnav__item--active" ariaCurrentWhenActive="page">
          <mat-icon aria-hidden="true">{{ item.icon }}</mat-icon>
          <span>{{ shortLabel(item.label) }}</span>
        </a>
      }
      <button type="button" class="bnav__item" (click)="layout.toggleSidebar()" [attr.aria-expanded]="!layout.sidebarCollapsed()">
        <mat-icon aria-hidden="true">menu</mat-icon>
        <span>Menu</span>
      </button>
    </nav>
  `,
  styles: [`
    .bnav {
      display: grid;
      grid-auto-flow: column;
      grid-auto-columns: 1fr;
      height: var(--bottomnav-height);
      background: var(--biat-white);
      border-top: 1px solid var(--biat-border);
    }
    .bnav__item {
      display: flex;
      flex-direction: column;
      align-items: center;
      justify-content: center;
      gap: 2px;
      border: 0;
      background: transparent;
      color: var(--biat-muted);
      font: 500 11px/1.2 var(--font-ui);
      text-decoration: none;
      cursor: pointer;
      position: relative;
    }
    .bnav__item mat-icon { font-size: 22px; width: 22px; height: 22px; }
    .bnav__item--active { color: var(--biat-primary); font-weight: 600; }
    .bnav__item--active::before {
      content: '';
      position: absolute;
      top: 0;
      left: 22%;
      right: 22%;
      height: 3px;
      border-radius: 0 0 3px 3px;
      background: var(--biat-primary);
    }
  `],
})
export class BottomNavComponent {
  readonly items = computed(() =>
    sectionsForRole(this.auth.role()).flatMap(s => s.items).filter(i => i.mobile),
  );

  constructor(
    private readonly auth: AuthService,
    readonly layout: LayoutService,
  ) {}

  shortLabel(label: string): string {
    return label === 'Assistant T24' ? 'Assistant' : label;
  }
}
