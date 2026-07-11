// Bilingual EN/中文 i18n framework (REQ-M4-06, AC maps TS-B-07).
// Full UI strings land per-module in Sprint 2-3; this establishes the switchable framework.
import i18n from 'i18next';
import { initReactI18next } from 'react-i18next';
import en from './en.json';
import zh from './zh.json';

export const SUPPORTED_LANGS = ['en', 'zh'] as const;
export type Lang = (typeof SUPPORTED_LANGS)[number];

i18n.use(initReactI18next).init({
  resources: { en: { translation: en }, zh: { translation: zh } },
  lng: (localStorage.getItem('tms.lang') as Lang) || 'en', // per-user, persisted (REQ-M4-06)
  fallbackLng: 'en',
  interpolation: { escapeValue: false },
});

export function setLang(lang: Lang) {
  i18n.changeLanguage(lang);
  localStorage.setItem('tms.lang', lang);
}

export default i18n;
