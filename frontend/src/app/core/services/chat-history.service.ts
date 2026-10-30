import { HttpClient, HttpContext, HttpParams } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { Chat, ChatContextUpdate, ChatHistory } from '../models/chat-session.model';
import { Page } from '../models/page.model';

/** Conversations persistées (Chat/ChatHistory), distinct de ChatbotService (POST /api/chat/ask). */
@Injectable({ providedIn: 'root' })
export class ChatHistoryService {
  private readonly chatsUrl = `${environment.apiUrl}/chats`;
  private readonly historyUrl = `${environment.apiUrl}/chat-history`;

  constructor(private readonly http: HttpClient) {}

  getChats(userId: number, page = 0, size = 20): Observable<Page<Chat>> {
    const params = new HttpParams().set('userId', userId).set('page', page).set('size', size);
    return this.http.get<Page<Chat>>(this.chatsUrl, { params });
  }

  /** `context` : voir http-context.ts (ex. SILENT_ERRORS quand l'appelant affiche lui-même « introuvable »). */
  getChatById(id: number, context?: HttpContext): Observable<Chat> {
    return this.http.get<Chat>(`${this.chatsUrl}/${id}`, { context });
  }

  createChat(title: string, userId: number): Observable<Chat> {
    return this.http.post<Chat>(this.chatsUrl, { title, userId });
  }

  /**
   * Renommer une conversation (PUT /api/chats/{id}, déjà pris en charge côté backend). ChatDTO
   * exige userId (@NotNull) même si ChatServiceImpl.update() ne s'en sert pas : on renvoie celui
   * de la conversation existante plutôt que de l'omettre (sinon 400 côté validation).
   */
  updateChat(chat: Chat, newTitle: string): Observable<Chat> {
    return this.http.put<Chat>(`${this.chatsUrl}/${chat.id}`, { title: newTitle, userId: chat.userId });
  }

  deleteChat(id: number): Observable<void> {
    return this.http.delete<void>(`${this.chatsUrl}/${id}`);
  }

  /** Fixe/change le contexte T24 (module/table/champ) d'une conversation, voir le parcours à bulles de /assistant. */
  updateContext(chatId: number, context: ChatContextUpdate): Observable<Chat> {
    return this.http.put<Chat>(`${this.chatsUrl}/${chatId}/context`, context);
  }

  getHistory(chatId: number, page = 0, size = 20): Observable<Page<ChatHistory>> {
    const params = new HttpParams().set('chatId', chatId).set('page', page).set('size', size);
    return this.http.get<Page<ChatHistory>>(this.historyUrl, { params });
  }

  deleteMessage(id: number): Observable<void> {
    return this.http.delete<void>(`${this.historyUrl}/${id}`);
  }
}
