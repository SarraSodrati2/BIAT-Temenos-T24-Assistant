/** Forme JSON d'un Spring Data `Page<T>` (voir Pageable côté backend : GET /api/modules, /api/table-t24, etc.). */
export interface Page<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  number: number;
  size: number;
  first: boolean;
  last: boolean;
  empty: boolean;
}
