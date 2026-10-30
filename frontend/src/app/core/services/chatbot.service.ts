import { HttpClient } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { ChatAskRequest, ChatAskResponse } from '../models/chat-session.model';

/** Le pipeline RAG (POST /api/chat/ask), utilisé par la page Assistant T24 (/assistant). */
@Injectable({ providedIn: 'root' })
export class ChatbotService {
  constructor(private readonly http: HttpClient) {}

  /** `chatId` : la conversation affichée, pour que le backend y enregistre l'échange (et non dans « la plus récente »). */
  ask(question: string, chatId?: number | null): Observable<ChatAskResponse> {
    const request: ChatAskRequest = chatId != null ? { question, chatId } : { question };
    return this.http.post<ChatAskResponse>(`${environment.apiUrl}/chat/ask`, request);
  }
}
