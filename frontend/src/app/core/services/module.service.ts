import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { ModuleT24 } from '../models/module-t24.model';
import { Page } from '../models/page.model';

@Injectable({ providedIn: 'root' })
export class ModuleService {
  private readonly baseUrl = `${environment.apiUrl}/modules`;

  constructor(private readonly http: HttpClient) {}

  getAll(page = 0, size = 20, sort = 'nomModule'): Observable<Page<ModuleT24>> {
    const params = new HttpParams().set('page', page).set('size', size).set('sort', sort);
    return this.http.get<Page<ModuleT24>>(this.baseUrl, { params });
  }

  getById(id: number): Observable<ModuleT24> {
    return this.http.get<ModuleT24>(`${this.baseUrl}/${id}`);
  }
}
