import { UserRole } from '../core/models/user.model';

export interface NavItem {
  label: string;
  icon: string;
  route: string;
  /** Rôles autorisés ; absent = tous les utilisateurs connectés. */
  roles?: UserRole[];
  /** Affiché dans la barre d'onglets du bas sur mobile. */
  mobile?: boolean;
}

export interface NavSection {
  label: string;
  items: NavItem[];
}

/** Source unique de la navigation (colonne latérale, onglets mobiles, titre de page). Les routes sont
 * celles d'origine de l'application : aucune n'a été renommée ni supprimée. */
export const NAV_SECTIONS: NavSection[] = [
  {
    label: 'Consulter',
    items: [
      { label: 'Accueil', icon: 'home', route: '/dashboard', mobile: true },
      { label: 'Assistant T24', icon: 'question_answer', route: '/assistant', mobile: true },
      { label: 'Historique', icon: 'history', route: '/chat-history' },
    ],
  },
  {
    label: 'Référentiel T24',
    items: [
      { label: 'Modules', icon: 'category', route: '/modules', mobile: true },
      { label: 'Tables', icon: 'table_chart', route: '/tables', mobile: true },
    ],
  },
  {
    label: 'Administration',
    items: [
      { label: 'Utilisateurs', icon: 'group', route: '/users', roles: ['ADMIN', 'SUPER_ADMIN'] },
      { label: 'Paramètres', icon: 'settings', route: '/settings', roles: ['SUPER_ADMIN'] },
    ],
  },
];

export const ABOUT_ITEM: NavItem = { label: 'À propos', icon: 'info', route: '/about' };

/** Sections visibles pour `role` (les sections vides sont retirées). */
export function sectionsForRole(role: UserRole | null): NavSection[] {
  return NAV_SECTIONS
    .map(section => ({
      ...section,
      items: section.items.filter(item => !item.roles || (role !== null && item.roles.includes(role))),
    }))
    .filter(section => section.items.length > 0);
}

/** Titre et section de la page correspondant à une URL (préfixe le plus long gagnant). */
export function describeRoute(url: string): { title: string; section: string | null } {
  const path = '/' + url.split(/[?#]/)[0].split('/').filter(Boolean)[0];
  for (const section of NAV_SECTIONS) {
    const found = section.items.find(item => item.route === path);
    if (found) {
      return { title: found.label, section: section.label };
    }
  }
  if (path === ABOUT_ITEM.route) {
    return { title: ABOUT_ITEM.label, section: null };
  }
  return { title: 'BIAT T24', section: null };
}
