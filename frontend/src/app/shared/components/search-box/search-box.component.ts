import { Component, ElementRef, HostListener, Input, OnDestroy, OnInit, ViewChild, computed, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { MatIconModule } from '@angular/material/icon';
import { Subject, Subscription, debounceTime, distinctUntilChanged, switchMap, tap } from 'rxjs';
import {
  MIN_QUERY_LENGTH,
  MatchKind,
  ReferentialSearchService,
  SearchResults,
} from '../../../core/services/referential-search.service';
import { RecentsService } from '../../../core/services/recents.service';

/** Une ligne sélectionnable du panneau (module, table, récent ou action). */
interface SearchOption {
  id: string;
  idx: number;
  kind: 'module' | 'table' | 'ask' | 'all-tables';
  label: string;
  sublabel?: string;
  match?: MatchKind;
  icon: string;
  go: () => void;
}

interface SearchGroup {
  title: string;
  options: SearchOption[];
}

const DEBOUNCE_MS = 220;
const MAX_RECENTS_SHOWN = 6;

/**
 * Recherche documentaire (modules et tables T24) avec panneau de résultats accessible au clavier
 * (rôle ARIA « combobox/listbox », flèches, Entrée, Échap). Deux présentations : « hero » (accueil) et
 * « compact » (barre du haut, raccourci « / » ou Ctrl+K). Les résultats viennent du vrai backend
 * (voir ReferentialSearchService) ; un champ ne se recherche pas globalement (limite du backend,
 * rappelée dans le panneau) : on l'obtient via sa table ou via l'assistant.
 */
@Component({
  selector: 'app-search-box',
  standalone: true,
  imports: [FormsModule, MatIconModule],
  templateUrl: './search-box.component.html',
  styleUrl: './search-box.component.scss',
})
export class SearchBoxComponent implements OnInit, OnDestroy {
  @Input() variant: 'hero' | 'compact' = 'compact';
  @Input() placeholder = 'Rechercher un module ou une table T24…';
  @ViewChild('field') field!: ElementRef<HTMLInputElement>;

  readonly uid = 'sb-' + Math.random().toString(36).slice(2, 8);
  readonly minLength = MIN_QUERY_LENGTH;

  readonly query = signal('');
  readonly open = signal(false);
  readonly loading = signal(false);
  readonly results = signal<SearchResults | null>(null);
  readonly activeIndex = signal(-1);

  private readonly typed$ = new Subject<string>();
  private subscription?: Subscription;

  readonly hasQuery = computed(() => this.query().trim().length >= MIN_QUERY_LENGTH);
  readonly showPanel = computed(() => this.open());

  /** Groupes affichés : résultats de la recherche, ou récents quand la saisie est vide. */
  readonly groups = computed<SearchGroup[]>(() => {
    const groups: SearchGroup[] = [];
    let idx = 0;
    const make = (o: Omit<SearchOption, 'idx' | 'id'>): SearchOption => {
      const option = { ...o, idx, id: `${this.uid}-opt-${idx}` };
      idx++;
      return option;
    };

    if (!this.hasQuery()) {
      const recents = this.recents.entries().slice(0, MAX_RECENTS_SHOWN);
      if (recents.length > 0) {
        groups.push({
          title: 'Consultés récemment',
          options: recents.map(r =>
            make({
              kind: r.kind,
              label: r.name,
              sublabel: r.kind === 'module' ? 'Module' : 'Table',
              icon: r.kind === 'module' ? 'category' : 'table_chart',
              go: () => this.openEntity(r.kind, r.name),
            }),
          ),
        });
      }
      return groups;
    }

    const results = this.results();
    if (!results) {
      return groups;
    }

    if (results.modules.length > 0) {
      groups.push({
        title: 'Modules',
        options: results.modules.map(m =>
          make({
            kind: 'module',
            label: m.name,
            sublabel: 'Module',
            match: m.match,
            icon: 'category',
            go: () => this.openEntity('module', m.name),
          }),
        ),
      });
    }
    if (results.tables.length > 0) {
      groups.push({
        title: 'Tables',
        options: results.tables.map(t =>
          make({
            kind: 'table',
            label: t.name,
            sublabel: t.module ? `Module ${t.module}` : 'Table',
            match: t.match,
            icon: 'table_chart',
            go: () => this.openEntity('table', t.name),
          }),
        ),
      });
    }

    const actions: SearchOption[] = [];
    if (results.tablesTotal > results.tables.length) {
      actions.push(
        make({
          kind: 'all-tables',
          label: `Voir les ${results.tablesTotal} tables contenant « ${results.query} »`,
          icon: 'list',
          go: () => this.router.navigate(['/tables'], { queryParams: { search: results.query } }).then(() => this.finish()),
        }),
      );
    }
    actions.push(
      make({
        kind: 'ask',
        label: `Demander à l'assistant : « ${results.query} »`,
        sublabel: 'Réponse tirée de la documentation indexée',
        icon: 'question_answer',
        go: () => this.router.navigate(['/assistant'], { queryParams: { q: results.query } }).then(() => this.finish()),
      }),
    );
    groups.push({ title: 'Aller plus loin', options: actions });
    return groups;
  });

  readonly options = computed(() => this.groups().flatMap(g => g.options));

  constructor(
    private readonly search: ReferentialSearchService,
    private readonly recents: RecentsService,
    private readonly router: Router,
    private readonly host: ElementRef<HTMLElement>,
  ) {}

  ngOnInit(): void {
    this.subscription = this.typed$
      .pipe(
        debounceTime(DEBOUNCE_MS),
        distinctUntilChanged(),
        tap(q => this.loading.set(q.trim().length >= MIN_QUERY_LENGTH)),
        switchMap(q => this.search.search(q)),
      )
      .subscribe(results => {
        this.results.set(results);
        this.loading.set(false);
        this.activeIndex.set(-1);
      });
  }

  ngOnDestroy(): void {
    this.subscription?.unsubscribe();
  }

  onInput(value: string): void {
    this.query.set(value);
    this.open.set(true);
    this.activeIndex.set(-1);
    if (value.trim().length < MIN_QUERY_LENGTH) {
      this.results.set(null);
      this.loading.set(false);
    }
    this.typed$.next(value);
  }

  retry(): void {
    this.loading.set(true);
    this.search.search(this.query()).subscribe(results => {
      this.results.set(results);
      this.loading.set(false);
    });
  }

  clear(): void {
    this.query.set('');
    this.results.set(null);
    this.typed$.next('');
    this.field.nativeElement.focus();
  }

  onKeydown(event: KeyboardEvent): void {
    const options = this.options();
    switch (event.key) {
      case 'ArrowDown':
        event.preventDefault();
        this.open.set(true);
        if (options.length > 0) {
          this.activeIndex.update(i => (i + 1) % options.length);
        }
        break;
      case 'ArrowUp':
        event.preventDefault();
        if (options.length > 0) {
          this.activeIndex.update(i => (i <= 0 ? options.length - 1 : i - 1));
        }
        break;
      case 'Enter': {
        event.preventDefault();
        const target = options[this.activeIndex()] ?? options[0];
        if (target) {
          target.go();
        }
        break;
      }
      case 'Escape':
        if (this.open()) {
          event.preventDefault();
          this.open.set(false);
        }
        break;
      case 'Tab':
        this.open.set(false);
        break;
    }
  }

  onFocus(): void {
    this.open.set(true);
  }

  matchLabel(match: MatchKind | undefined): string {
    return match === 'exact' ? 'Exact' : 'Approchant';
  }

  matchTitle(match: MatchKind | undefined): string {
    switch (match) {
      case 'exact': return 'Le nom est identique à votre saisie';
      case 'prefix': return 'Le nom commence par votre saisie';
      default: return 'Le nom contient votre saisie';
    }
  }

  /** Raccourcis globaux (barre du haut uniquement, pour ne pas dupliquer sur l'accueil) : « / » ou Ctrl/⌘+K. */
  @HostListener('window:keydown', ['$event'])
  onGlobalKeydown(event: KeyboardEvent): void {
    if (this.variant !== 'compact') {
      return;
    }
    const isShortcut = (event.key === 'k' && (event.ctrlKey || event.metaKey)) || (event.key === '/' && !this.isTyping(event.target));
    if (isShortcut) {
      event.preventDefault();
      this.field.nativeElement.focus();
      this.field.nativeElement.select();
    }
  }

  @HostListener('document:mousedown', ['$event'])
  onDocumentMouseDown(event: MouseEvent): void {
    if (this.open() && !this.host.nativeElement.contains(event.target as Node)) {
      this.open.set(false);
    }
  }

  private isTyping(target: EventTarget | null): boolean {
    const el = target as HTMLElement | null;
    return !!el && (el.tagName === 'INPUT' || el.tagName === 'TEXTAREA' || el.isContentEditable);
  }

  private openEntity(kind: 'module' | 'table', name: string): void {
    const route = kind === 'module' ? '/modules' : '/tables';
    this.router.navigate([route], { queryParams: { [kind]: name } }).then(() => this.finish());
  }

  private finish(): void {
    this.open.set(false);
    this.query.set('');
    this.results.set(null);
    // Réarme distinctUntilChanged : retaper la même recherche doit de nouveau produire des résultats.
    this.typed$.next('');
    this.field.nativeElement.blur();
  }
}
