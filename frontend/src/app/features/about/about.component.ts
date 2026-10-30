import { Component } from '@angular/core';
import { RouterLink } from '@angular/router';
import { MatIconModule } from '@angular/material/icon';

/**
 * « À propos » : explique honnêtement comment l'application produit ses réponses et ses limites.
 * Contenu statique décrivant le fonctionnement réel du système (aucune donnée d'exemple).
 */
@Component({
  selector: 'app-about',
  standalone: true,
  imports: [RouterLink, MatIconModule],
  template: `
    <div class="ui-page about">
      <header class="about__head">
        <p class="ui-eyebrow">À propos</p>
        <h1 class="ui-display about__title">Comment l'assistant répond, et ce qu'il ne fait pas.</h1>
        <p class="ui-lede">
          Le Référentiel T24 de BIAT IT permet de consulter la documentation Temenos T24 (fichiers XML et HelpText
          indexés) par recherche ou en posant une question en français.
        </p>
      </header>

      <section class="about__section" aria-labelledby="ab-1">
        <h2 id="ab-1" class="ui-eyebrow">Ce que fait l'assistant</h2>
        <ol class="about__steps">
          <li><strong>Il cherche</strong> dans la documentation indexée les passages qui correspondent à votre question, dans le périmètre du module choisi.</li>
          <li><strong>Il répond uniquement à partir de ces passages</strong> : fonction du champ, exemple, attributs.</li>
          <li><strong>Il cite sa source</strong> : la table d'origine et l'extrait documentaire exact qui justifie la réponse.</li>
        </ol>
      </section>

      <section class="about__section" aria-labelledby="ab-2">
        <h2 id="ab-2" class="ui-eyebrow">Ce qu'il ne fait pas</h2>
        <ul class="about__list">
          <li>Il n'invente ni champ, ni exemple, ni règle : si la recherche dans la documentation n'a pas permis de confirmer l'information, il le dit clairement, sans en conclure qu'elle n'existe pas.</li>
          <li>Il n'utilise pas de connaissances générales sur T24 comme s'il s'agissait de la documentation.</li>
          <li>Il ne couvre que les modules proposés à l'ouverture d'une conversation. Le reste du référentiel se consulte dans <a routerLink="/modules">Modules</a> et <a routerLink="/tables">Tables</a>.</li>
          <li>La recherche globale porte sur les modules et les tables. Un champ se retrouve en ouvrant sa table (filtre de champs) ou en interrogeant l'assistant.</li>
        </ul>
      </section>

      <section class="about__section" aria-labelledby="ab-3">
        <h2 id="ab-3" class="ui-eyebrow">Lire une réponse</h2>
        <dl class="about__defs">
          <div><dt>Champ</dt><dd>Le nom exact du champ T24 concerné.</dd></div>
          <div><dt>Fonction</dt><dd>Son rôle, reformulé à partir de la documentation.</dd></div>
          <div><dt>Exemple</dt><dd>Un exemple tiré de la documentation, ou l'indication qu'il n'y en a pas.</dd></div>
          <div><dt>Source</dt><dd>La table dont provient l'information ; un clic l'ouvre dans le référentiel.</dd></div>
          <div><dt>Extrait documentaire</dt><dd>Le passage original, non traduit, qui justifie la réponse.</dd></div>
          <div><dt>Attributs</dt><dd>Règles de validation, type, saisie, caractère obligatoire, valeur par défaut — ou « Non renseigné ».</dd></div>
        </dl>
      </section>

      <section class="about__section" aria-labelledby="ab-4">
        <h2 id="ab-4" class="ui-eyebrow">Raccourcis et données</h2>
        <ul class="about__list">
          <li><kbd class="ui-kbd">/</kbd> ou <kbd class="ui-kbd">Ctrl</kbd> + <kbd class="ui-kbd">K</kbd> : placer le curseur dans la recherche.</li>
          <li><kbd class="ui-kbd">Entrée</kbd> envoie la question ; <kbd class="ui-kbd">Maj</kbd> + <kbd class="ui-kbd">Entrée</kbd> ajoute une ligne.</li>
          <li>Vos conversations sont enregistrées sur votre compte (renommables et supprimables depuis l'assistant et l'historique). La liste « Consultés récemment » est mémorisée uniquement dans ce navigateur.</li>
          <li>Le thème clair ou sombre se change depuis le premier bouton de la barre du haut ; votre choix est conservé.</li>
        </ul>
      </section>
    </div>
  `,
  styles: [`
    .about { display: flex; flex-direction: column; gap: 36px; padding-top: 44px; max-width: 820px; }
    .about__head { display: flex; flex-direction: column; gap: 14px; }
    .about__title { font-size: clamp(28px, 3.8vw, 42px); max-width: 22ch; }
    .about__section { display: flex; flex-direction: column; gap: 12px; padding-top: 24px; border-top: 1px solid var(--biat-border); }
    .about__steps, .about__list { margin: 0; padding-left: 22px; display: flex; flex-direction: column; gap: 10px; line-height: 1.6; }
    .about__list a, .about__steps a { color: var(--biat-primary); }
    .about__defs { margin: 0; display: grid; gap: 0; }
    .about__defs > div { display: grid; grid-template-columns: 190px minmax(0, 1fr); gap: 16px; padding: 12px 0; border-bottom: 1px solid var(--biat-border); }
    .about__defs dt { font: 500 14px/1.5 var(--font-mono); color: var(--biat-primary); }
    .about__defs dd { margin: 0; color: var(--biat-text); }
    @media (max-width: 599px) { .about__defs > div { grid-template-columns: 1fr; gap: 4px; } }
  `],
})
export class AboutComponent {}
