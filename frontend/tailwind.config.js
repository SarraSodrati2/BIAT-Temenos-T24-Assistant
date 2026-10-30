/** @type {import('tailwindcss').Config} */
module.exports = {
  content: ["./src/**/*.{html,ts}"],
  corePlugins: {
    preflight: false,
  },
  theme: {
    extend: {
      colors: {
        'biat-primary': '#1D4E89',
        'biat-secondary': '#2D6FB7',
        'biat-light': '#EAF2FB',
        'biat-bg': '#F8FAFC',
        'biat-text': '#1E293B',
      },
      fontFamily: {
        sans: ['Roboto', '"Segoe UI"', 'Arial', 'sans-serif'],
      },
      boxShadow: {
        card: '0 1px 3px rgba(30, 41, 59, 0.08), 0 1px 2px rgba(30, 41, 59, 0.06)',
        'card-hover': '0 4px 12px rgba(30, 41, 59, 0.10)',
      },
      borderRadius: {
        xl: '14px',
        '2xl': '18px',
      },
    },
  },
  plugins: [],
}
