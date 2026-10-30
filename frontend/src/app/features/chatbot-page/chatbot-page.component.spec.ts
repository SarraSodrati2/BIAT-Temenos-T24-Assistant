import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { ChatbotPageComponent } from './chatbot-page.component';
import { ChatContextService } from '../../core/services/chat-context.service';
import { environment } from '../../../environments/environment';

/**
 * Vérifie le rendu Markdown -> HTML des réponses de l'assistant (voir `formatAiContent` et le
 * template, qui bind `[innerHTML]="formatAiContent(message.content)"` pour les messages IA au lieu
 * de `{{ message.content }}`, lequel afficherait le Markdown brut sans l'interpréter), ainsi que le
 * titre automatique de conversation et la reprise d'une conversation existante (voir `send()` et
 * `ngOnInit()`).
 *
 * `ChatContextService` est remplacé par un stub minimal (mêmes signaux/méthodes publics utilisés
 * par le composant) plutôt que la vraie implémentation, pour éviter d'avoir à satisfaire toute sa
 * propre chaîne de dépendances (AuthService/UserService/ModuleService/TableService) — hors sujet ici.
 */
describe('ChatbotPageComponent', () => {
  function chatContextStub(overrides: { chatId?: number | null; resumedWithHistory?: boolean; newConversationRequested?: boolean } = {}) {
    return {
      newConversationRequested: signal(overrides.newConversationRequested ?? false),
      step: signal<'loading' | 'module' | 'done'>('done'),
      chatId: signal<number | null>('chatId' in overrides ? (overrides.chatId as number | null) : 1),
      modules: signal<{ id: number; nomModule: string }[]>([]),
      tables: signal<{ nomTable: string }[]>([]),
      selectedModule: signal<string | null>('MD'),
      resumedWithHistory: signal(overrides.resumedWithHistory ?? false),
      initForCurrentUser: jasmine.createSpy('initForCurrentUser'),
      renameCurrentChat: jasmine.createSpy('renameCurrentChat'),
      newConversation: () => undefined,
      selectModule: () => undefined,
      reset: () => undefined,
    };
  }

  async function createComponent(overrides: { chatId?: number | null; resumedWithHistory?: boolean; newConversationRequested?: boolean } = {}) {
    const stub = chatContextStub(overrides);
    await TestBed.configureTestingModule({
      imports: [ChatbotPageComponent],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: ChatContextService, useValue: stub },
      ],
    }).compileComponents();

    const fixture = TestBed.createComponent(ChatbotPageComponent);
    return { fixture, chatContext: stub };
  }

  describe('formatAiContent() — rendu Markdown -> HTML sûr', () => {
    it("rend exactement l'exemple Champ/Fonction/Exemple/Source/Extrait documentaire sans laisser " +
      "aucun \"**\" ni \">\" brut visible", async () => {
      const { fixture } = await createComponent();
      const component = fixture.componentInstance;
      const content =
        '**Champ :** `DEAL.SUB.TYP`\n\n' +
        '**Fonction**\n\n' +
        "Ce champ est un identifiant défini par l'utilisateur qui permet de préciser\n" +
        'le type de `MD.DEAL` à saisir.\n\n' +
        '**Exemple**\n\n' +
        '`GTREC` correspond à **Guarantee Received**.\n\n' +
        '**Source**\n\n' +
        '`MD.PARAMETER`\n\n' +
        '**Extrait documentaire**\n\n' +
        '> "This is a user defined identifier for the type of MD.DEAL that is to be entered."';

      const html = component.formatAiContent(content);
      const container = document.createElement('div');
      container.innerHTML = html;

      // Aucun marqueur Markdown brut ne doit survivre dans le texte affiché.
      expect(container.textContent).not.toContain('**');
      expect(container.textContent?.trim().startsWith('>')).toBeFalse();
      expect(html).not.toContain('> "This is a user defined identifier');

      // Titres de section rendus comme tels.
      const headings = Array.from(container.querySelectorAll('.msg-heading')).map(el => el.textContent);
      expect(headings).toEqual(['Fonction', 'Exemple', 'Source', 'Extrait documentaire']);

      // Noms de champs/tables/codes T24 en code inline.
      const codeTexts = Array.from(container.querySelectorAll('code')).map(el => el.textContent);
      expect(codeTexts).toEqual(['DEAL.SUB.TYP', 'MD.DEAL', 'GTREC', 'MD.PARAMETER']);

      // Fonction reformulée fusionnée en un seul paragraphe (pas coupée par le retour à la ligne
      // du texte source), sans info perdue.
      expect(container.querySelector('.msg-line')?.textContent).toBe(
        "Champ : DEAL.SUB.TYP",
      );
      const paragraphs = Array.from(container.querySelectorAll('.msg-line')).map(el => el.textContent);
      expect(paragraphs).toContain(
        "Ce champ est un identifiant défini par l'utilisateur qui permet de préciser le type de MD.DEAL à saisir.",
      );

      // Mise en gras conservée pour "Guarantee Received".
      const strongTexts = Array.from(container.querySelectorAll('strong')).map(el => el.textContent);
      expect(strongTexts).toContain('Guarantee Received');

      // Extrait documentaire dans un vrai bloc de citation, texte exact conservé.
      const quote = container.querySelector('blockquote.msg-quote');
      expect(quote).toBeTruthy();
      expect(quote?.textContent).toBe(
        '"This is a user defined identifier for the type of MD.DEAL that is to be entered."',
      );
    });

    it('donne à chaque section sa propre classe pour la hiérarchie visuelle (Champ badge, ' +
      'Fonction la plus lisible, Exemple en bloc clair, Source en badge)', async () => {
      const { fixture } = await createComponent();
      const content =
        '**Champ :** `DEAL.SUB.TYP`\n\n' +
        '**Fonction**\n\n' +
        "Ce champ est un identifiant.\n\n" +
        '**Exemple**\n\n' +
        '`GTREC` correspond à **Guarantee Received**.\n\n' +
        '**Source**\n\n' +
        '`MD.PARAMETER`\n\n' +
        '**Extrait documentaire**\n\n' +
        '> "Texte original."';

      const container = document.createElement('div');
      container.innerHTML = fixture.componentInstance.formatAiContent(content);

      expect(container.querySelector('.msg-line.msg-champ')?.textContent).toBe('Champ : DEAL.SUB.TYP');
      expect(container.querySelector('.msg-line.msg-fonction')?.textContent).toBe('Ce champ est un identifiant.');
      expect(container.querySelector('.msg-line.msg-exemple')?.textContent).toContain('GTREC');
      expect(container.querySelector('.msg-line.msg-source')?.textContent).toBe('MD.PARAMETER');
      expect(container.querySelector('.msg-heading--fonction')?.textContent).toBe('Fonction');
      expect(container.querySelector('.msg-heading--exemple')?.textContent).toBe('Exemple');
      expect(container.querySelector('.msg-heading--source')?.textContent).toBe('Source');
      expect(container.querySelector('.msg-heading--extrait')?.textContent).toBe('Extrait documentaire');
    });

    it('applique la même hiérarchie visuelle quand les titres portent un ":" final ' +
      '("**Fonction :**") — format actuellement demandé par le prompt backend', async () => {
      const { fixture } = await createComponent();
      const content =
        '**Champ :** `DEAL.SUB.TYP`\n\n' +
        '**Fonction :**\n\n' +
        "Ce champ est un identifiant.\n\n" +
        '**Exemple :**\n\n' +
        '`GTREC` correspond à **Guarantee Received**.\n\n' +
        '**Source :**\n\n' +
        '`MD.PARAMETER`\n\n' +
        '**Extrait documentaire :**\n\n' +
        '> "Texte original."';

      const container = document.createElement('div');
      container.innerHTML = fixture.componentInstance.formatAiContent(content);

      // Le ":" reste affiché tel quel dans le libellé...
      expect(container.querySelector('.msg-heading--fonction')?.textContent).toBe('Fonction :');
      expect(container.querySelector('.msg-heading--exemple')?.textContent).toBe('Exemple :');
      expect(container.querySelector('.msg-heading--source')?.textContent).toBe('Source :');
      expect(container.querySelector('.msg-heading--extrait')?.textContent).toBe('Extrait documentaire :');
      // ...mais n'empêche pas la reconnaissance de section : les classes de hiérarchie visuelle
      // (Fonction la plus lisible, Exemple en bloc clair, Source en badge) s'appliquent toujours.
      expect(container.querySelector('.msg-line.msg-fonction')?.textContent).toBe('Ce champ est un identifiant.');
      expect(container.querySelector('.msg-line.msg-exemple')?.textContent).toContain('GTREC');
      expect(container.querySelector('.msg-line.msg-source')?.textContent).toBe('MD.PARAMETER');
    });

    it("replie l'Extrait documentaire long : seule la première phrase de définition est visible " +
      "directement, le reste repliable, sans jamais couper au milieu d'un identifiant T24 en pointillé " +
      '(ex. "MD.DEAL") — reproduit le vrai Extrait renvoyé par le backend pour DEAL.SUB.TYP', async () => {
      const { fixture } = await createComponent();
      const longExtrait =
        'This is a user defined identifier for the type of MD.DEAL that is to be entered. Given that the ' +
        'name of the application is Miscellaneous Deals it can be envisaged that the types of use this ' +
        'application will see will vary according to the individual banks that use it. As an example the ' +
        'following structure could be entered where GTREC is a Guarantee Received.';
      const content = `**Extrait documentaire**\n\n> "${longExtrait}"`;

      const container = document.createElement('div');
      container.innerHTML = fixture.componentInstance.formatAiContent(content);

      const quote = container.querySelector('blockquote.msg-quote');
      expect(quote).toBeTruthy();

      // Seule la première phrase (jusqu'au point qui suit "entered", PAS celui de "MD.DEAL" qui n'a
      // pas d'espace après lui) est visible avant dépliage.
      const lead = container.querySelector('.msg-quote__lead');
      expect(lead?.textContent).toBe('"This is a user defined identifier for the type of MD.DEAL that is to be entered.');
      expect(lead?.textContent).not.toContain('Given that');

      // Le reste est replié dans un <details>/<summary> natif — rien de caché de façon irréversible.
      const more = container.querySelector('details.msg-quote__more');
      expect(more).toBeTruthy();
      expect(more?.querySelector('summary')?.textContent).toBe("Afficher l'extrait complet");

      // Rien n'est perdu : lead + le contenu (hors libellé "Afficher...") du bloc replié
      // reconstituent le texte original au caractère près.
      const restSpan = more?.querySelector('span');
      expect((lead?.textContent ?? '') + (restSpan?.textContent ?? '')).toBe(`"${longExtrait}"`);
      expect(more?.textContent).toContain('Given that the name of the application');
      expect(more?.textContent).toContain('GTREC is a Guarantee Received');
    });

    it("n'affiche pas de bouton \"Afficher plus\" pour un Extrait déjà court", async () => {
      const { fixture } = await createComponent();
      const container = document.createElement('div');
      container.innerHTML = fixture.componentInstance.formatAiContent(
        '**Extrait documentaire**\n\n> "This is a user defined identifier for the type of MD.DEAL."',
      );

      expect(container.querySelector('details.msg-quote__more')).toBeNull();
      expect(container.querySelector('blockquote.msg-quote')?.textContent).toBe(
        '"This is a user defined identifier for the type of MD.DEAL."',
      );
    });

    it("affiche exactement la phrase de repli quand aucun exemple n'est fourni", async () => {
      const { fixture } = await createComponent();
      const html = fixture.componentInstance.formatAiContent(
        '**Exemple**\n\nAucun exemple explicite n\'est fourni dans la documentation.',
      );
      const container = document.createElement('div');
      container.innerHTML = html;

      expect(container.querySelector('.msg-heading')?.textContent).toBe('Exemple');
      expect(container.querySelector('.msg-line')?.textContent).toBe(
        "Aucun exemple explicite n'est fourni dans la documentation.",
      );
    });

    it("échappe tout HTML/script contenu dans la réponse : aucune injection possible", async () => {
      const { fixture } = await createComponent();
      const html = fixture.componentInstance.formatAiContent(
        '**Fonction**\n\n<img src=x onerror=alert(1)> <script>alert(1)</script>',
      );
      const container = document.createElement('div');
      container.innerHTML = html;

      expect(container.querySelector('img')).toBeNull();
      expect(container.querySelector('script')).toBeNull();
      expect(container.textContent).toContain('<img src=x onerror=alert(1)>');
      expect(container.textContent).toContain('<script>alert(1)</script>');
    });

    it('ne traduit ni ne reformule rien côté frontend : le texte anglais reste tel quel', async () => {
      const { fixture } = await createComponent();
      const html = fixture.componentInstance.formatAiContent(
        '**Function**\n\nThis field is a user-defined identifier for the type of `MD.DEAL`.',
      );
      const container = document.createElement('div');
      container.innerHTML = html;

      expect(container.textContent).toContain('This field is a user-defined identifier for the type of MD.DEAL.');
    });
  });

  describe('affichage des messages dans le template', () => {
    it("un message IA passe par [innerHTML]=formatAiContent(...), pas par {{ message.content }} brut", async () => {
      const { fixture } = await createComponent();
      const component = fixture.componentInstance;
      component.messages.set([
        {
          id: 1,
          sender: 'AI',
          content: '**Fonction**\n\n`GTREC` correspond à **Guarantee Received**.',
          persisted: true,
        },
      ]);
      fixture.detectChanges();

      const bubble: HTMLElement | null = fixture.nativeElement.querySelector('.chat-bubble--ai-rich');
      expect(bubble).toBeTruthy();
      expect(bubble!.textContent).not.toContain('**');
      expect(bubble!.querySelector('.msg-heading')?.textContent).toBe('Fonction');
      expect(bubble!.querySelector('code')?.textContent).toBe('GTREC');
      expect(bubble!.querySelector('strong')?.textContent).toBe('Guarantee Received');
    });

    it('un message USER reste affiché tel quel (aucun rendu Markdown appliqué)', async () => {
      const { fixture } = await createComponent();
      const component = fixture.componentInstance;
      component.messages.set([
        { id: 2, sender: 'USER', content: 'Question avec des ** astérisques **', persisted: true },
      ]);
      fixture.detectChanges();

      const bubble: HTMLElement | null = fixture.nativeElement.querySelector('.chat-bubble--user');
      expect(bubble?.textContent?.trim()).toBe('Question avec des ** astérisques **');
    });
  });

  describe('reprise de conversation depuis la barre latérale', () => {
    it("ngOnInit() ne resynchronise PAS sur la conversation la plus récente quand une conversation " +
      "est déjà active (ouverte depuis la barre latérale juste avant la navigation)", async () => {
      const { fixture, chatContext } = await createComponent({ chatId: 42 });

      fixture.detectChanges();

      expect(chatContext.initForCurrentUser).not.toHaveBeenCalled();
    });

    it("ngOnInit() résout normalement la conversation la plus récente quand aucune n'est encore active", async () => {
      const { fixture, chatContext } = await createComponent({ chatId: null });

      fixture.detectChanges();

      expect(chatContext.initForCurrentUser).toHaveBeenCalledTimes(1);
    });

    it("« Nouvelle » cliqué dans la colonne de navigation depuis une autre page : crée la nouvelle conversation " +
      "à l'arrivée, sans d'abord rouvrir la plus récente", async () => {
      const { fixture, chatContext } = await createComponent({ chatId: null, newConversationRequested: true });
      const created = spyOn(chatContext, 'newConversation');

      fixture.detectChanges();

      expect(created).toHaveBeenCalledTimes(1);
      expect(chatContext.initForCurrentUser).not.toHaveBeenCalled();
      expect(chatContext.newConversationRequested()).toBeFalse();
    });

    it("« Nouvelle » cliqué dans la colonne de navigation alors que l'Assistant est déjà affiché", async () => {
      const { fixture, chatContext } = await createComponent({ chatId: 7 });
      const created = spyOn(chatContext, 'newConversation');
      fixture.detectChanges();

      chatContext.newConversationRequested.set(true);
      fixture.detectChanges();
      await fixture.whenStable();
      await Promise.resolve();

      expect(created).toHaveBeenCalledTimes(1);
      expect(chatContext.newConversationRequested()).toBeFalse();
    });
  });

  describe('titre automatique de conversation (send())', () => {
    it("la toute première question d'une conversation neuve devient son titre", async () => {
      const { fixture, chatContext } = await createComponent({ resumedWithHistory: false });
      const httpMock = TestBed.inject(HttpTestingController);
      fixture.detectChanges();
      const component = fixture.componentInstance;

      component.draft = '  Quelle est la fonction de DEAL.SUB.TYP ?  ';
      component.send();

      const req = httpMock.expectOne(`${environment.apiUrl}/chat/ask`);
      req.flush({ answer: 'réponse', authenticationRequired: false, userMessageId: 101, assistantMessageId: 102 });

      expect(chatContext.renameCurrentChat).toHaveBeenCalledOnceWith('Quelle est la fonction de DEAL.SUB.TYP ?');
      httpMock.verify();
    });

    it("ne renomme jamais une conversation reprise depuis l'historique (elle a déjà son titre)", async () => {
      const { fixture, chatContext } = await createComponent({ resumedWithHistory: true });
      const httpMock = TestBed.inject(HttpTestingController);
      fixture.detectChanges();
      // resumedWithHistory=true déclenche aussi le rechargement de l'historique (voir l'effet dédié
      // dans le constructeur) : une conversation reprise a réellement un historique à recharger.
      // loadHistory() est différé via queueMicrotask (voir ChatbotPageComponent, correctif NG0600 —
      // écriture dans un signal pendant un effect) : il faut laisser ce microtask s'exécuter avant
      // que la requête HTTP n'apparaisse.
      await Promise.resolve();
      httpMock.expectOne(req => req.url === `${environment.apiUrl}/chat-history`)
        .flush({ content: [], totalElements: 0, totalPages: 0, number: 0, size: 200 });
      const component = fixture.componentInstance;

      component.draft = 'Et le format ?';
      component.send();

      const req = httpMock.expectOne(`${environment.apiUrl}/chat/ask`);
      req.flush({ answer: 'réponse', authenticationRequired: false, userMessageId: 201, assistantMessageId: 202 });

      expect(chatContext.renameCurrentChat).not.toHaveBeenCalled();
      httpMock.verify();
    });

    it('ne renomme la conversation qu\'une seule fois, jamais aux questions suivantes', async () => {
      const { fixture, chatContext } = await createComponent({ resumedWithHistory: false });
      const httpMock = TestBed.inject(HttpTestingController);
      fixture.detectChanges();
      const component = fixture.componentInstance;

      component.draft = 'Première question';
      component.send();
      httpMock.expectOne(`${environment.apiUrl}/chat/ask`)
        .flush({ answer: 'réponse 1', authenticationRequired: false, userMessageId: 301, assistantMessageId: 302 });

      component.draft = 'Deuxième question';
      component.send();
      httpMock.expectOne(`${environment.apiUrl}/chat/ask`)
        .flush({ answer: 'réponse 2', authenticationRequired: false, userMessageId: 303, assistantMessageId: 304 });

      expect(chatContext.renameCurrentChat).toHaveBeenCalledTimes(1);
      httpMock.verify();
    });
  });

  describe("envoi d'une question : jamais de doublon", () => {
    it("un double envoi (double clic / Entrée + bouton) ne déclenche qu'une seule requête et qu'un seul message", async () => {
      const { fixture } = await createComponent();
      const httpMock = TestBed.inject(HttpTestingController);
      fixture.detectChanges();
      const component = fixture.componentInstance;

      component.draft = 'quel champ contient le code SWIFT dans TT ?';
      component.send();
      component.send(); // 2e clic pendant l'attente : ignoré
      component.draft = 'quel champ contient le code SWIFT dans TT ?';
      component.send(); // même texte ressaisi pendant l'attente : ignoré aussi

      const req = httpMock.expectOne(`${environment.apiUrl}/chat/ask`);
      req.flush({ answer: 'réponse', authenticationRequired: false, userMessageId: 11, assistantMessageId: 12 });

      const userMessages = component.messages().filter(m => m.sender === 'USER');
      const aiMessages = component.messages().filter(m => m.sender === 'AI' && m.id === 12);
      expect(userMessages.length).toBe(1);
      expect(aiMessages.length).toBe(1);
      httpMock.verify();
    });

    it("après la réponse, une nouvelle question part normalement (l'envoi n'est bloqué que pendant l'attente)", async () => {
      const { fixture } = await createComponent();
      const httpMock = TestBed.inject(HttpTestingController);
      fixture.detectChanges();
      const component = fixture.componentInstance;

      component.draft = 'Première';
      component.send();
      httpMock.expectOne(`${environment.apiUrl}/chat/ask`)
        .flush({ answer: 'r1', authenticationRequired: false, userMessageId: 21, assistantMessageId: 22 });
      expect(component.loading()).toBeFalse();

      component.draft = 'Seconde';
      component.send();
      httpMock.expectOne(`${environment.apiUrl}/chat/ask`)
        .flush({ answer: 'r2', authenticationRequired: false, userMessageId: 23, assistantMessageId: 24 });

      expect(component.messages().filter(m => m.sender === 'USER').length).toBe(2);
      httpMock.verify();
    });

    it("« Réessayer » pendant une attente n'envoie pas une seconde requête", async () => {
      const { fixture } = await createComponent();
      const httpMock = TestBed.inject(HttpTestingController);
      fixture.detectChanges();
      const component = fixture.componentInstance;

      component.draft = 'Question';
      component.send();
      component.retry();

      httpMock.expectOne(`${environment.apiUrl}/chat/ask`).flush({ answer: 'ok', authenticationRequired: false });
      httpMock.verify();
    });

    it("transmet la conversation affichée au backend (chatId) pour que l'échange y soit enregistré", async () => {
      const { fixture } = await createComponent({ chatId: 77 });
      const httpMock = TestBed.inject(HttpTestingController);
      fixture.detectChanges();
      const component = fixture.componentInstance;

      component.draft = 'Question';
      component.send();

      const req = httpMock.expectOne(`${environment.apiUrl}/chat/ask`);
      expect(req.request.body).toEqual({ question: 'Question', chatId: 77 });
      req.flush({ answer: 'ok', authenticationRequired: false });
      httpMock.verify();
    });
  });
});
