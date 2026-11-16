/** @type {import('tailwindcss').Config} */
export default {
  content: ['./index.html', './src/**/*.{ts,tsx}'],
  theme: {
    extend: {
      colors: {
        // The sidebar and headings. One navy family, so the whole product reads as one thing.
        navy: {
          50: '#f2f5fa',
          100: '#e1e8f2',
          200: '#c3d0e3',
          400: '#6f88ad',
          600: '#2c4a78',
          700: '#1f3760',
          800: '#16294a',
          900: '#0f1f3d',
          950: '#0a1630',
        },
      },
      fontFamily: {
        sans: ['Inter', 'Segoe UI', 'system-ui', '-apple-system', 'Roboto', 'Helvetica Neue', 'Arial', 'sans-serif'],
        mono: ['ui-monospace', 'SFMono-Regular', 'Consolas', 'Liberation Mono', 'monospace'],
      },
    },
  },
  plugins: [],
};
