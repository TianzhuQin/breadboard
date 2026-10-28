'use strict'
const webpack = require('webpack')
const path = require('path')
const VueLoaderPlugin = require('vue-loader/lib/plugin')
const MiniCssExtractPlugin = require('mini-css-extract-plugin')
const buildPath = path.resolve(__dirname, '../public/bundles/')
// The minifier webpack itself uses (it isn't a dependency of this package).
const TerserPlugin = require(require.resolve('terser-webpack-plugin', {
  paths: [path.dirname(require.resolve('webpack/package.json'))],
}))
// The AngularJS bundles (the previous console, and older experiments' AngularJS page) let Angular
// find services by parameter name, e.g. directive('compile', function ($compile, $timeout) ...),
// so minifying them must keep the names.
const ANGULAR_BUNDLES = /^(design|client-angular)\.js$/

const PORT = 8765
const isProd = process.env.NODE_ENV === 'production'
// BB_SOURCEMAPS=1 adds separate source maps to a production build.
const sourceMaps = /^(1|true|yes)$/i.test(process.env.BB_SOURCEMAPS || '')
// 'auto': chunks load from wherever the entry script was served (breadboard.assetsRoot, e.g. a CDN).
const publicPath = isProd
  ? 'auto'
  : `http://localhost:${PORT}/bundles/`
// webpack-dev-server v3's client isn't injected when launched via webpack-cli's
// `webpack serve`, so the HMR websocket never connects. Inject it manually in dev.
let devClient = isProd
  ? []
  : [
      `webpack-dev-server/client?http://localhost:${PORT}`,
      'webpack/hot/dev-server',
    ]
devClient = []
const plugins = [
  new webpack.ContextReplacementPlugin(
    /angular(\\|\/)core(\\|\/)@angular/,
    path.resolve(__dirname, './design'),
  ),
  new VueLoaderPlugin(),
  new webpack.DefinePlugin({
    __VUE_PROD_DEVTOOLS__: true,
  }),
]
if (isProd) {
  plugins.push(
    new MiniCssExtractPlugin({
      filename: '[name].css',
      chunkFilename: '[name].[contenthash:8].css',
    }),
  )
}
module.exports = {
  entry: {
    client: [...devClient, './client/src/client.ts'],
    breadboard: [...devClient, './core/src/breadboard.ts'],
    design: [...devClient, './design/design.js'],
    'client-angular': [...devClient, './design/client.js'],
    // vue: ['vue', 'vuetify'],
    // 'vue-components': {
    //   import: './client/vue-components.ts',
    //   dependOn: 'vue'
    // },
    // graph: './client/lib/graph.ts'
  },
  cache: {
    type: 'filesystem',
    // The cache doesn't follow devtool: builds with and without source maps keep one each.
    name: `${isProd ? 'production' : 'development'}${sourceMaps ? '-sourcemaps' : ''}`,
  },
  output: {
    path: buildPath,
    publicPath: publicPath,
    // Entry bundles keep fixed names (the server's pages link to them, and browsers revalidate
    // them: see assets.cache in conf/application.conf). Chunks loaded on demand carry a content
    // hash, so a browser never runs a stale one after an update.
    chunkFilename: isProd ? '[name].[contenthash:8].js' : '[name].js',
    clean: true,
  },
  mode: isProd ? 'production' : 'development',
  // Production bundles carry no source maps unless BB_SOURCEMAPS is set, and then in separate .map
  // files: the eval'd maps of development builds made design.js about 21 MB and client.js 5.6 MB.
  devtool: isProd ? (sourceMaps ? 'source-map' : false) : 'eval-source-map',
  devServer: {
    port: PORT,
    hot: true,
    // wds v4+ removed the top-level `publicPath` option; serving path now lives
    // under `devMiddleware`. `allowedHosts: 'all'` preserves v3's behavior of
    // accepting requests regardless of Host (the backend-served page loads these
    // bundles cross-origin).
    allowedHosts: 'all',
    devMiddleware: {
      publicPath: '/bundles/',
    },
    client: {
      // Bundles + the HMR client are served cross-origin from this dev server,
      // so point the HMR websocket back at it explicitly instead of letting it
      // infer the (wrong) backend origin from the page URL.
      webSocketURL: `ws://localhost:${PORT}/ws`,
    },
    headers: {
      'Access-Control-Allow-Origin': '*',
      'Access-Control-Allow-Methods': 'GET, POST, PUT, DELETE, PATCH, OPTIONS',
      'Access-Control-Allow-Headers':
        'X-Requested-With, content-type, Authorization',
    },
  },
  module: {
    rules: [
      {
        test: /\.vue$/,
        loader: 'vue-loader',
      },
      {
        test: /\.scss$/,
        use: [
          isProd ? MiniCssExtractPlugin.loader : 'vue-style-loader',
          'css-loader',
          {
            loader: 'sass-loader',
            options: {
              implementation: require('sass'),
            },
          },
        ],
        // exclude: /node_modules/,
      },
      {
        test: /\.sass$/,
        use: [
          isProd ? MiniCssExtractPlugin.loader : 'vue-style-loader',
          'css-loader',
          {
            loader: 'sass-loader',
            options: {
              implementation: require('sass'),
              sassOptions: {
                indentedSyntax: true,
              },
            },
          },
        ],
      },
      {
        test: /\.css$/,
        use: ['style-loader', 'css-loader'],
        // exclude: /node_modules/,
      },
      {
        test: /\.tsx?$/,
        use: [
          {
            loader: 'ts-loader',
            options: {
              transpileOnly: true,
              appendTsSuffixTo: [/\.vue$/],
            },
          },
        ],
        // exclude: /(goodish|gremlins-ts)/
      },
      {
        test: /\.js$/,
        use: {
          loader: 'babel-loader',
          options: {
            presets: ['@babel/preset-env'],
          },
        },
        // exclude: {
        //   include: /node_modules/,
        //   exclude: /goodish/
        // }
      },
      {
        test: /\.html$/,
        use: [
          'ngtemplate-loader?relativeTo=frontend&prefix=files',
          'html-loader',
        ],
        exclude: /node_modules/,
      },
      {
        test: /\.(jpg|png|gif|webp|tiff)$/,
        use: 'url-loader',
        exclude: /node_modules/,
      },
      {
        test: /\.svg$/,
        use: 'url-loader?limit=10000&mimetype=image/svg+xml',
        exclude: /node_modules/,
      },
      {
        test: /\.(eot|svg|ttf|woff|woff2)$/,
        use: {
          loader: 'file-loader',
          options: {
            name: '[contenthash].[ext]',
          },
        },
      },
    ],
  },
  resolve: {
    extensions: [
      '.js',
      '.json',
      '.css',
      '.html',
      '.jsx',
      '.ts',
      '.tsx',
      '.vue',
    ],
    // prevents multiple copies of vue being loaded
    alias: {
      vue$: 'vue/dist/vue.js', // 'vue/dist/vue.common.js' for webpack 1
    },
  },
  // externals: {
  //   vue: 'Vue'
  // }
  optimization: {
    minimizer: [
      new TerserPlugin({ exclude: ANGULAR_BUNDLES }),
      new TerserPlugin({ include: ANGULAR_BUNDLES, terserOptions: { mangle: false } }),
    ],
    splitChunks: {
      chunks: 'async',
      cacheGroups: {
        vendor: {
          test: /[\\/]node_modules[\\/]/,
          name: 'vendors',
          chunks: 'async',
        },
      },
    },
  },
  plugins,
}
