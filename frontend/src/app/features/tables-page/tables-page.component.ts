import { Component, Input, OnDestroy, OnInit, computed, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { HttpContext } from '@angular/common/http';
import { Router } from '@angular/router';
import { MatIconModule } from '@angular/material/icon';
import { MatTableModule } from '@angular/material/table';
import { MatPaginatorModule, PageEvent } from '@angular/material/paginator';
import { MatSortModule, Sort } from '@angular/material/sort';
import { Subject, Subscription, debounceTime, distinctUntilChanged } from 'rxjs';
import { SILENT_ERRORS } from '../../core/interceptors/http-context';
import { ReferentialSearchService } from '../../core/services/referential-search.service';
import { RecentsService } from '../../core/services/recents.service';
import { TableService } from '../../core/services/table.service';
import { FieldInfo, TableT24Dto } from '../../core/models/table-t24.model';

const SEARCH_DEBOUNCE_MS = 300;

/**
 * Catalogue des tables T24 : liste paginée/triée (ou résultats de recherche du backend) et panneau de
 * détail affichant les champs documentés (type, obligatoire, longueur, valeur par défaut, validation).
 * `?table=NOM` ouvre directement une table ; `?search=texte` lance une recherche (depuis la recherche
 * globale ou la page Modules).
 */
@Component({
  selector: 'app-tables-page',
  standalone: true,
  imports: [FormsModule, DatePipe, MatIconModule, MatTableModule, MatPaginatorModule, MatSortModule],
  templateUrl: './tables-page.component.html',
  styleUrl: './tables-page.component.scss',
})
export class TablesPageComponent implements OnInit, OnDestroy {
  /** Paramètre d'URL `?table=` (lié par withComponentInputBinding). */
  @Input() set table(name: string | undefined) {
    const clean = name?.trim();
    if (clean) {
      this.openByName(clean);
    }
  }

  /** Paramètre d'URL `?search=`. */
  @Input() set search(value: string | undefined) {
    const clean = value?.trim() ?? '';
    if (clean !== this.searchTerm()) {
      this.searchTerm.set(clean);
      this.pageIndex = 0;
      if (this.initialized) {
        this.load();
      }
    }
  }

  readonly displayedColumns = ['nomTable', 'module', 'updatedAt'];
  readonly tables = signal<TableT24Dto[] | null>(null);
  readonly totalElements = signal(0);
  readonly loadFailed = signal(false);
  readonly searchTerm = signal('');

  readonly selected = signal<TableT24Dto | null>(null);
  readonly fields = signal<FieldInfo[] | null>(null);
  readonly fieldsFailed = signal(false);
  readonly fieldFilter = signal('');
  readonly notFound = signal<string | null>(null);
  readonly copied = signal(false);

  readonly visibleFields = computed(() => {
    const list = this.fields() ?? [];
    const term = this.fieldFilter().trim().toLowerCase();
    return term
      ? list.filter(f => f.name.toLowerCase().includes(term) || (f.description ?? '').toLowerCase().includes(term))
      : list;
  });

  pageIndex = 0;
  pageSize = 20;
  sortField = 'nomTable';
  sortDirection: 'asc' | 'desc' = 'asc';

  private initialized = false;
  private modulesById = new Map<number, string>();
  private readonly typed$ = new Subject<string>();
  private typedSub?: Subscription;
  private loadSub?: Subscription;

  constructor(
    private readonly tableService: TableService,
    private readonly searchService: ReferentialSearchService,
    private readonly recents: RecentsService,
    private readonly router: Router,
  ) {}

  ngOnInit(): void {
    this.searchService.modules().subscribe(modules => {
      this.modulesById = new Map(modules.map(m => [m.id, m.nomModule]));
      // Force le rafraîchissement des cellules « Module » une fois le catalogue connu.
      this.tables.update(t => (t ? [...t] : t));
    });

    this.typedSub = this.typed$.pipe(debounceTime(SEARCH_DEBOUNCE_MS), distinctUntilChanged()).subscribe(term => {
      this.searchTerm.set(term.trim());
      this.pageIndex = 0;
      this.load();
    });

    this.initialized = true;
    this.load();
  }

  ngOnDestroy(): void {
    this.typedSub?.unsubscribe();
    this.loadSub?.unsubscribe();
  }

  moduleName(table: TableT24Dto): string {
    return this.modulesById.get(table.moduleId) ?? '—';
  }

  isExact(table: TableT24Dto): boolean {
    const term = this.searchTerm();
    return !!term && table.nomTable.toLowerCase() === term.toLowerCase();
  }

  onSearchInput(value: string): void {
    this.typed$.next(value);
  }

  submitSearch(value: string): void {
    this.searchTerm.set(value.trim());
    this.pageIndex = 0;
    this.load();
  }

  clearSearch(): void {
    this.searchTerm.set('');
    this.typed$.next('');
    this.pageIndex = 0;
    this.load();
  }

  onPage(event: PageEvent): void {
    this.pageIndex = event.pageIndex;
    this.pageSize = event.pageSize;
    this.load();
  }

  onSort(sort: Sort): void {
    if (!sort.direction) {
      this.sortField = 'nomTable';
      this.sortDirection = 'asc';
    } else {
      this.sortField = sort.active;
      this.sortDirection = sort.direction;
    }
    this.load();
  }

  /** Ouvre une table de la liste (les données de la ligne suffisent, pas de nouvel appel pour l'en-tête). */
  open(table: TableT24Dto): void {
    this.notFound.set(null);
    this.select(table);
  }

  close(): void {
    this.selected.set(null);
    this.fields.set(null);
    // Retire ?table= de l'URL pour qu'un rafraîchissement ne rouvre pas le panneau.
    this.router.navigate([], { queryParams: { table: null }, queryParamsHandling: 'merge', replaceUrl: true });
  }

  retryFields(): void {
    const table = this.selected();
    if (table) {
      this.loadFields(table.nomTable);
    }
  }

  askAssistant(table: TableT24Dto): void {
    this.router.navigate(['/assistant'], { queryParams: { q: `Décris la table ${table.nomTable}` } });
  }

  copyName(table: TableT24Dto): void {
    navigator.clipboard.writeText(table.nomTable).then(() => {
      this.copied.set(true);
      setTimeout(() => this.copied.set(false), 1500);
    });
  }

  searchFor(term: string): void {
    this.notFound.set(null);
    this.searchTerm.set(term);
    this.pageIndex = 0;
    this.load();
  }

  fieldMeta(field: FieldInfo): { label: string; tone: 'ok' | 'warn' | 'neutral' }[] {
    const tags: { label: string; tone: 'ok' | 'warn' | 'neutral' }[] = [];
    if (field.type) {
      tags.push({ label: field.type, tone: 'neutral' });
    }
    if (field.mandatory === true) {
      tags.push({ label: 'Obligatoire', tone: 'warn' });
    } else if (field.mandatory === false) {
      tags.push({ label: 'Facultatif', tone: 'neutral' });
    }
    if (field.length != null) {
      tags.push({ label: `Longueur ${field.length}`, tone: 'neutral' });
    }
    return tags;
  }

  // --- interne ---------------------------------------------------------------------------------

  private select(table: TableT24Dto): void {
    this.selected.set(table);
    this.fields.set(null);
    this.fieldsFailed.set(false);
    this.fieldFilter.set('');
    this.recents.record('table', table.nomTable);
    this.loadFields(table.nomTable);
  }

  private loadFields(nomTable: string): void {
    this.fieldsFailed.set(false);
    this.fields.set(null);
    this.tableService.getFields(nomTable).subscribe({
      next: fields => {
        if (this.selected()?.nomTable === nomTable) {
          this.fields.set(fields);
        }
      },
      error: () => {
        if (this.selected()?.nomTable === nomTable) {
          this.fieldsFailed.set(true);
          this.fields.set([]);
        }
      },
    });
  }

  /** Ouverture par nom (URL) : une table inconnue n'affiche pas de notification globale, mais un message dans la page. */
  private openByName(nomTable: string): void {
    this.notFound.set(null);
    this.tableService.getByName(nomTable, new HttpContext().set(SILENT_ERRORS, true)).subscribe({
      next: table => this.select(table),
      error: () => this.notFound.set(nomTable),
    });
  }

  private load(): void {
    this.loadSub?.unsubscribe();
    this.tables.set(null);
    this.loadFailed.set(false);
    const sort = `${this.sortField},${this.sortDirection}`;
    const term = this.searchTerm();

    const request$ = term
      ? this.tableService.search(term, this.pageIndex, this.pageSize, sort)
      : this.tableService.getAll(this.pageIndex, this.pageSize, sort);

    this.loadSub = request$.subscribe({
      next: page => {
        this.tables.set(page.content);
        this.totalElements.set(page.totalElements);
      },
      error: () => {
        this.loadFailed.set(true);
        this.tables.set([]);
        this.totalElements.set(0);
      },
    });
  }
}
