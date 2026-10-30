import { HttpClient, HttpContext, HttpParams } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { Page } from '../models/page.model';
import { FieldInfo, TableT24Dto } from '../models/table-t24.model';

@Injectable({ providedIn: 'root' })
export class TableService {
  private readonly baseUrl = `${environment.apiUrl}/table-t24`;

  constructor(private readonly http: HttpClient) {}

  getAll(page = 0, size = 20, sort = 'nomTable'): Observable<Page<TableT24Dto>> {
    return this.http.get<Page<TableT24Dto>>(this.baseUrl, { params: this.pageParams(page, size, sort) });
  }

  /** `context` : voir http-context.ts (ex. backgroundRequestContext() pour les suggestions en cours de frappe). */
  search(keyword: string, page = 0, size = 20, sort = 'nomTable', context?: HttpContext): Observable<Page<TableT24Dto>> {
    const params = this.pageParams(page, size, sort).set('keyword', keyword);
    return this.http.get<Page<TableT24Dto>>(`${this.baseUrl}/search`, { params, context });
  }

  getByModule(moduleCode: string, page = 0, size = 20, sort = 'nomTable'): Observable<Page<TableT24Dto>> {
    return this.http.get<Page<TableT24Dto>>(`${this.baseUrl}/module/${moduleCode}`, {
      params: this.pageParams(page, size, sort),
    });
  }

  getByName(nomTable: string, context?: HttpContext): Observable<TableT24Dto> {
    return this.http.get<TableT24Dto>(`${this.baseUrl}/${encodeURIComponent(nomTable)}`, { context });
  }

  getFields(nomTable: string): Observable<FieldInfo[]> {
    return this.http.get<FieldInfo[]>(`${this.baseUrl}/${encodeURIComponent(nomTable)}/fields`);
  }

  private pageParams(page: number, size: number, sort: string): HttpParams {
    return new HttpParams().set('page', page).set('size', size).set('sort', sort);
  }
}
