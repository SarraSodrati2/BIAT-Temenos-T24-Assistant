import { Component, Input, OnInit, computed, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { MatIconModule } from '@angular/material/icon';
import { ALLOWED_MODULE_CODES } from '../../core/services/chat-context.service';
import { ModuleService } from '../../core/services/module.service';
import { RecentsService } from '../../core/services/recents.service';
import { TableService } from '../../core/services/table.service';
import { ModuleT24 } from '../../core/models/module-t24.model';
import { TableT24Dto } from '../../core/models/table-t24.model';

/** Le catalogue de modules T24 est borné (~416 codes) : un seul appel large, filtrage 100 % côté client. */
const MODULES_PAGE_SIZE = 500;
const TABLES_PAGE_SIZE = 50;

interface LetterGroup {
  letter: string;
  modules: ModuleT24[];
}

/**
 * Répertoire des modules T24 : index alphabétique + panneau de détail listant les tables du module
 * (pagination réelle du backend, filtre local). `?module=CODE` ouvre directement un module (depuis la
 * recherche globale ou l'accueil).
 */
@Component({
  selector: 'app-modules-page',
  standalone: true,
  imports: [FormsModule, RouterLink, MatIconModule],
  templateUrl: './modules-page.component.html',
  styleUrl: './modules-page.component.scss',
})
export class ModulesPageComponent implements OnInit {
  /** Paramètre d'URL `?module=` (lié par withComponentInputBinding). */
  @Input() set module(code: string | undefined) {
    this.requestedCode = code?.trim() || null;
    this.openRequested();
  }

  readonly modules = signal<ModuleT24[] | null>(null);
  readonly loadFailed = signal(false);
  readonly filter = signal('');

  readonly selected = signal<ModuleT24 | null>(null);
  readonly tables = signal<TableT24Dto[] | null>(null);
  readonly tablesTotal = signal(0);
  readonly tablesFailed = signal(false);
  readonly loadingMore = signal(false);
  readonly tableFilter = signal('');

  readonly assistantModules = ALLOWED_MODULE_CODES;

  readonly filtered = computed(() => {
    const all = this.modules() ?? [];
    const term = this.filter().trim().toLowerCase();
    return term ? all.filter(m => m.nomModule.toLowerCase().includes(term)) : all;
  });

  /** Modules regroupés par initiale (A–Z, puis « # » pour tout autre caractère). */
  readonly groups = computed<LetterGroup[]>(() => {
    const byLetter = new Map<string, ModuleT24[]>();
    for (const m of this.filtered()) {
      const first = m.nomModule.charAt(0).toUpperCase();
      const letter = /[A-Z]/.test(first) ? first : '#';
      byLetter.set(letter, [...(byLetter.get(letter) ?? []), m]);
    }
    return [...byLetter.entries()]
      .sort(([a], [b]) => (a === '#' ? 1 : b === '#' ? -1 : a.localeCompare(b)))
      .map(([letter, modules]) => ({ letter, modules }));
  });

  readonly visibleTables = computed(() => {
    const list = this.tables() ?? [];
    const term = this.tableFilter().trim().toLowerCase();
    return term ? list.filter(t => t.nomTable.toLowerCase().includes(term)) : list;
  });

  private requestedCode: string | null = null;
  private nextPage = 0;

  constructor(
    private readonly moduleService: ModuleService,
    private readonly tableService: TableService,
    private readonly recents: RecentsService,
    private readonly router: Router,
  ) {}

  ngOnInit(): void {
    this.loadModules();
  }

  loadModules(): void {
    this.loadFailed.set(false);
    this.moduleService.getAll(0, MODULES_PAGE_SIZE).subscribe({
      next: page => {
        this.modules.set(page.content);
        this.openRequested();
      },
      error: () => {
        this.loadFailed.set(true);
        this.modules.set([]);
      },
    });
  }

  isAssistantModule(code: string): boolean {
    return this.assistantModules.includes(code);
  }

  select(module: ModuleT24): void {
    this.selected.set(module);
    this.tables.set(null);
    this.tablesFailed.set(false);
    this.tableFilter.set('');
    this.tablesTotal.set(0);
    this.nextPage = 0;
    this.recents.record('module', module.nomModule);
    this.fetchTables(module);
  }

  loadMore(): void {
    const module = this.selected();
    if (module && !this.loadingMore()) {
      this.tablesFailed.set(false);
      this.fetchTables(module);
    }
  }

  scrollToLetter(letter: string): void {
    document
      .getElementById('lettre-' + (letter === '#' ? 'autre' : letter))
      ?.scrollIntoView({ behavior: 'smooth', block: 'start' });
  }

  close(): void {
    this.selected.set(null);
    this.tables.set(null);
    // Retire ?module= de l'URL pour qu'un rafraîchissement ne rouvre pas le panneau.
    this.router.navigate([], { queryParams: { module: null }, queryParamsHandling: 'merge', replaceUrl: true });
  }

  hasMoreTables(): boolean {
    return (this.tables()?.length ?? 0) < this.tablesTotal();
  }

  askAssistant(module: ModuleT24): void {
    this.router.navigate(['/assistant'], { queryParams: { q: `À quoi sert le module ${module.nomModule} ?` } });
  }

  private fetchTables(module: ModuleT24): void {
    const first = this.nextPage === 0;
    if (!first) {
      this.loadingMore.set(true);
    }
    this.tableService.getByModule(module.nomModule, this.nextPage, TABLES_PAGE_SIZE).subscribe({
      next: page => {
        // Ignore une réponse tardive si l'utilisateur a changé de module entre-temps.
        if (this.selected()?.id !== module.id) {
          return;
        }
        this.tables.update(current => [...(current ?? []), ...page.content]);
        this.tablesTotal.set(page.totalElements);
        this.nextPage++;
        this.loadingMore.set(false);
      },
      error: () => {
        if (this.selected()?.id === module.id) {
          this.tablesFailed.set(true);
          this.loadingMore.set(false);
          if (first) {
            this.tables.set([]);
          }
        }
      },
    });
  }

  /** Ouvre le module demandé par l'URL dès que le catalogue est disponible (insensible à la casse). */
  private openRequested(): void {
    const all = this.modules();
    if (!all || !this.requestedCode) {
      return;
    }
    const code = this.requestedCode.toLowerCase();
    const found = all.find(m => m.nomModule.toLowerCase() === code);
    if (found && this.selected()?.id !== found.id) {
      this.select(found);
    }
  }
}
