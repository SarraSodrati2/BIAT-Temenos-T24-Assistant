import { Injectable } from '@angular/core';
import { Observable, catchError, forkJoin, map, of, shareReplay, switchMap } from 'rxjs';
import { ModuleService } from './module.service';
import { TableService } from './table.service';
import { backgroundRequestContext } from '../interceptors/http-context';
import { ModuleT24 } from '../models/module-t24.model';
import { TableT24Dto } from '../models/table-t24.model';

/** exact = nom identique ; prefix = le nom commence par la saisie ; contains = la saisie apparaît dans le nom. */
export type MatchKind = 'exact' | 'prefix' | 'contains';

export interface ModuleHit {
  type: 'module';
  name: string;
  match: MatchKind;
}

export interface TableHit {
  type: 'table';
  name: string;
  /** Code du module propriétaire, si résolu. */
  module: string | null;
  match: MatchKind;
}

export interface SearchResults {
  query: string;
  modules: ModuleHit[];
  tables: TableHit[];
  /** Nombre total de tables correspondantes côté backend (peut dépasser `tables`, limité à l'affichage). */
  tablesTotal: number;
  /** true si l'appel de recherche de tables a échoué (serveur injoignable, session expirée...). */
  failed: boolean;
}

export const MIN_QUERY_LENGTH = 2;
const MODULES_FETCH_SIZE = 500;
const MAX_MODULE_HITS = 6;
const MAX_TABLE_HITS = 8;
/** Assez large pour que les correspondances exactes/préfixes remontent malgré le tri alphabétique du backend. */
const TABLE_FETCH_SIZE = 20;

const RANK: Record<MatchKind, number> = { exact: 0, prefix: 1, contains: 2 };

/** Nature de la correspondance entre `candidate` et `query` (insensible à la casse), ou null si aucune. */
export function matchKind(candidate: string, query: string): MatchKind | null {
  const q = query.trim().toLowerCase();
  const c = candidate.toLowerCase();
  if (!q) {
    return null;
  }
  if (c === q) {
    return 'exact';
  }
  if (c.startsWith(q)) {
    return 'prefix';
  }
  return c.includes(q) ? 'contains' : null;
}

function byRankThenName<T extends { match: MatchKind; name: string }>(a: T, b: T): number {
  return RANK[a.match] - RANK[b.match] || a.name.localeCompare(b.name);
}

/**
 * Recherche documentaire à partir de ce que le backend sait réellement faire :
 *  - modules : filtrage local sur le catalogue (~416 codes, chargé une seule fois) ;
 *  - tables : GET /api/table-t24/search (sous-chaîne, insensible à la casse) + recherche exacte du nom ;
 *  - champs : le backend n'offre PAS de recherche de champ transversale — un champ se retrouve en ouvrant
 *    sa table (filtre de champs) ou en interrogeant l'assistant.
 * La distinction « exact / commence par / contient » est calculée ici sur les résultats réels.
 */
@Injectable({ providedIn: 'root' })
export class ReferentialSearchService {
  private modules$?: Observable<ModuleT24[]>;

  constructor(
    private readonly moduleService: ModuleService,
    private readonly tableService: TableService,
  ) {}

  /** Catalogue de modules, chargé une fois puis partagé. */
  modules(): Observable<ModuleT24[]> {
    if (!this.modules$) {
      this.modules$ = this.moduleService.getAll(0, MODULES_FETCH_SIZE).pipe(
        map(page => page.content),
        catchError(() => {
          this.modules$ = undefined;
          return of([] as ModuleT24[]);
        }),
        shareReplay(1),
      );
    }
    return this.modules$;
  }

  search(rawQuery: string): Observable<SearchResults> {
    const query = rawQuery.trim();
    if (query.length < MIN_QUERY_LENGTH) {
      return of({ query, modules: [], tables: [], tablesTotal: 0, failed: false });
    }

    return this.modules().pipe(
      switchMap(modules => {
        const moduleNameById = new Map(modules.map(m => [m.id, m.nomModule]));
        const moduleHits: ModuleHit[] = modules
          .map(m => ({ m, match: matchKind(m.nomModule, query) }))
          .filter((x): x is { m: ModuleT24; match: MatchKind } => x.match !== null)
          .map(x => ({ type: 'module' as const, name: x.m.nomModule, match: x.match }))
          .sort(byRankThenName)
          .slice(0, MAX_MODULE_HITS);

        const context = backgroundRequestContext();
        const exact$ = this.tableService.getByName(query, context).pipe(catchError(() => of(null)));
        const list$ = this.tableService.search(query, 0, TABLE_FETCH_SIZE, 'nomTable', context).pipe(
          map(page => ({ rows: page.content, total: page.totalElements, failed: false })),
          catchError(() => of({ rows: [] as TableT24Dto[], total: 0, failed: true })),
        );

        return forkJoin([exact$, list$]).pipe(
          map(([exact, list]) => {
            const byName = new Map<string, TableT24Dto>();
            [...(exact ? [exact] : []), ...list.rows].forEach(t => byName.set(t.nomTable, t));

            const tableHits: TableHit[] = [...byName.values()]
              .map(t => ({
                type: 'table' as const,
                name: t.nomTable,
                module: moduleNameById.get(t.moduleId) ?? null,
                match: matchKind(t.nomTable, query) ?? 'contains',
              }))
              .sort(byRankThenName)
              .slice(0, MAX_TABLE_HITS);

            return {
              query,
              modules: moduleHits,
              tables: tableHits,
              tablesTotal: Math.max(list.total, tableHits.length),
              failed: list.failed,
            };
          }),
        );
      }),
    );
  }
}
