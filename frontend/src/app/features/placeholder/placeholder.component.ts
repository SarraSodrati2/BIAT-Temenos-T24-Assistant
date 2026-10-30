import { Component, Input } from '@angular/core';
import { MatIconModule } from '@angular/material/icon';

@Component({
  selector: 'app-placeholder',
  standalone: true,
  imports: [MatIconModule],
  template: `
    <div class="placeholder">
      <mat-icon>{{ icon }}</mat-icon>
      <h2>{{ title }}</h2>
      <p>Cette section arrive bientôt.</p>
    </div>
  `,
  styles: [`
    .placeholder {
      display: flex;
      flex-direction: column;
      align-items: center;
      justify-content: center;
      gap: 8px;
      min-height: 60vh;
      color: var(--biat-muted);

      mat-icon { font-size: 48px; width: 48px; height: 48px; color: var(--biat-muted-light); }
      h2 { color: var(--biat-text); margin: 4px 0 0; }
      p { margin: 0; font-size: 14px; }
    }
  `],
})
export class PlaceholderComponent {
  @Input() title = '';
  @Input() icon = 'info';
}
