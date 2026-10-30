import { Component } from '@angular/core';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ThemeService } from '../../../core/services/theme.service';

/**
 * Cadre commun de la connexion et de l'inscription : panneau de présentation à gauche (masqué sur
 * mobile) et formulaire projeté à droite. La bascule clair/sombre est présente dès cet écran, le
 * premier que voit l'utilisateur.
 */
@Component({
  selector: 'app-auth-layout',
  standalone: true,
  imports: [MatIconModule, MatTooltipModule],
  templateUrl: './auth-layout.component.html',
  styleUrl: './auth-layout.component.scss',
})
export class AuthLayoutComponent {
  constructor(readonly theme: ThemeService) {}
}
