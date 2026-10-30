import { Routes } from '@angular/router';
import { ShellComponent } from './layout/shell/shell.component';
import { PlaceholderComponent } from './features/placeholder/placeholder.component';
import { LoginComponent } from './features/login/login.component';
import { RegisterComponent } from './features/register/register.component';
import { HomeComponent } from './features/home/home.component';
import { AboutComponent } from './features/about/about.component';
import { ModulesPageComponent } from './features/modules-page/modules-page.component';
import { TablesPageComponent } from './features/tables-page/tables-page.component';
import { AssistantWorkspaceComponent } from './features/assistant/assistant-workspace.component';
import { HistoryPageComponent } from './features/history-page/history-page.component';
import { UsersPageComponent } from './features/users-page/users-page.component';
import { authGuard } from './core/guards/auth.guard';
import { adminGuard } from './core/guards/admin.guard';
import { entryGuard } from './core/guards/entry.guard';
import { roleGuard } from './core/guards/role.guard';

export const routes: Routes = [
  // « / » redirige (accueil si connecté, connexion sinon) ; l'ancienne page d'accueil publique a été
  // remplacée par l'accueil connecté ci-dessous.
  { path: '', pathMatch: 'full', canActivate: [entryGuard], children: [] },
  { path: 'login', component: LoginComponent },
  { path: 'register', component: RegisterComponent },
  {
    path: '',
    component: ShellComponent,
    canActivate: [authGuard],
    children: [
      { path: 'dashboard', component: HomeComponent },
      // Assistant T24 : point d'entrée unique de la consultation conversationnelle (chatbot RAG, contexte
      // module, liste des conversations) ; « ?q= » pré-remplit la question (recherche globale, modules, tables).
      { path: 'assistant', component: AssistantWorkspaceComponent },
      { path: 'chatbot', redirectTo: 'assistant' }, // ancienne route, conservée en redirection
      { path: 'modules', component: ModulesPageComponent },
      { path: 'tables', component: TablesPageComponent },
      { path: 'chat-history', component: HistoryPageComponent },
      { path: 'chat-history/:id', component: HistoryPageComponent },
      { path: 'about', component: AboutComponent },
      { path: 'users', component: UsersPageComponent, canActivate: [adminGuard] },
      {
        path: 'settings',
        component: PlaceholderComponent,
        data: { title: 'Paramètres', icon: 'settings' },
        canActivate: [roleGuard('SUPER_ADMIN')],
      },
    ],
  },
  { path: '**', redirectTo: '' },
];
