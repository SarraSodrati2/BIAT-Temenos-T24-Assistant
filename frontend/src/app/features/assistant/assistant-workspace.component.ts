import { Component, Input, computed } from '@angular/core';
import { ChatContextService } from '../../core/services/chat-context.service';
import { ConversationsService } from '../../core/services/conversations.service';
import { LayoutService } from '../../core/services/layout.service';
import { ChatbotPageComponent } from '../chatbot-page/chatbot-page.component';

const DEFAULT_TITLE = 'Nouvelle conversation';

/**
 * Espace Assistant (route /assistant) : la conversation active. La liste des conversations n'est plus ici
 * mais dans la colonne de navigation, sous « Utilisateurs » (demande du 2026-10-06, voir RailNavComponent) ;
 * sur petit écran, le bouton « Conversations » ouvre ce tiroir. La question éventuellement passée en paramètre
 * d'URL `q` (depuis la recherche globale) est seulement pré-remplie, jamais envoyée automatiquement.
 */
@Component({
  selector: 'app-assistant-workspace',
  standalone: true,
  imports: [ChatbotPageComponent],
  templateUrl: './assistant-workspace.component.html',
  styleUrl: './assistant-workspace.component.scss',
})
export class AssistantWorkspaceComponent {
  /** Paramètre d'URL `?q=` (lié automatiquement par withComponentInputBinding). */
  @Input() q?: string;

  /** Titre de la conversation active, lu dans la liste partagée. */
  readonly title = computed(() => {
    const id = this.chatContext.chatId();
    const chat = this.conversations.conversations()?.find(c => c.id === id);
    return chat?.title ?? DEFAULT_TITLE;
  });

  constructor(
    private readonly chatContext: ChatContextService,
    private readonly conversations: ConversationsService,
    readonly layout: LayoutService,
  ) {}
}
