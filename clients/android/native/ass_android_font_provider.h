/* SPDX-License-Identifier: GPL-3.0-or-later
 * Android API29 font matching for the pinned libass 0.17.5 trial.
 * Included in ass_fontselect.c, after its internal provider declarations.
 * No global font enumeration, Fontconfig cache, or Java/JNI byte transfer.
 */
#include <android/font.h>
#include <android/font_matcher.h>
#include <dlfcn.h>
#include FT_MULTIPLE_MASTERS_H

typedef struct {
    void *handle;
    FT_Library ft;
    ASS_FontProvider *provider;
    AFontMatcher *(*create)(void);
    void (*destroy)(AFontMatcher *);
    void (*style)(AFontMatcher *, uint16_t, bool);
    AFont *(*match)(const AFontMatcher *, const char *, const uint16_t *, uint32_t, uint32_t *);
    void (*close)(AFont *);
    const char *(*path)(const AFont *);
    size_t (*index)(const AFont *);
    size_t (*axis_count)(const AFont *);
    uint32_t (*axis_tag)(const AFont *, uint32_t);
    float (*axis_value)(const AFont *, uint32_t);
    uint16_t (*weight)(const AFont *);
    bool (*italic)(const AFont *);
} NfbAndroidFonts;

static bool nfb_android_has_glyph(void *data, uint32_t code)
{
    return !code || FT_Get_Char_Index((FT_Face)data, code) != 0;
}

static void nfb_android_destroy_font(void *data)
{
    FT_Done_Face((FT_Face)data);
}

static void nfb_android_destroy(void *data)
{
    NfbAndroidFonts *fonts = data;
    if (fonts->handle) dlclose(fonts->handle);
    free(fonts);
}

/* libass opens a fresh FT face by path/index. Encode an exact named variable
 * instance into that index, rather than losing Android's variation settings.
 * Unrepresentable axes are rejected and logged, never advertised as honoured.
 */
static bool nfb_android_instance(NfbAndroidFonts *fonts, AFont *font,
                                 FT_Face face, int *index)
{
    size_t count = fonts->axis_count(font);
    if (!count) return true;
    FT_MM_Var *vars = NULL;
    if (FT_Get_MM_Var(face, &vars)) return false;
    bool found = false;
    for (size_t a = 0; a < count; a++) {
        bool known = false;
        for (FT_UInt axis = 0; axis < vars->num_axis; axis++)
            known |= fonts->axis_tag(font, (uint32_t)a) == vars->axis[axis].tag;
        if (!known) { FT_Done_MM_Var(fonts->ft, vars); return false; }
    }
    for (FT_UInt instance = 0; instance <= vars->num_namedstyles; instance++) {
        bool equal = true;
        for (FT_UInt axis = 0; axis < vars->num_axis; axis++) {
            FT_Fixed expected = vars->axis[axis].def;
            for (size_t a = 0; a < count; a++)
                if (fonts->axis_tag(font, (uint32_t)a) == vars->axis[axis].tag)
                    expected = (FT_Fixed)lroundf(fonts->axis_value(font, (uint32_t)a) * 65536.f);
            FT_Fixed actual = instance ? vars->namedstyle[instance - 1].coords[axis] : vars->axis[axis].def;
            if (actual != expected) equal = false;
        }
        if (equal && instance < 0x8000) {
            *index |= (int)(instance << 16);
            found = true;
            break;
        }
    }
    FT_Done_MM_Var(fonts->ft, vars);
    return found;
}

static bool nfb_android_add(NfbAndroidFonts *fonts, ASS_Library *lib,
                            const char *family, const char *logical_name,
                            uint32_t code, uint16_t weight, bool italic)
{
    uint16_t text[2];
    uint32_t length = 1;
    if (code > 0x10ffff || (code >= 0xd800 && code <= 0xdfff)) return false;
    if (code > 0xffff) {
        code -= 0x10000;
        text[0] = 0xd800 | (code >> 10); text[1] = 0xdc00 | (code & 0x3ff);
        length = 2;
    } else text[0] = code;
    AFontMatcher *matcher = fonts->create();
    if (!matcher) return false;
    fonts->style(matcher, weight, italic);
    AFont *font = fonts->match(matcher, family, text, length, NULL);
    fonts->destroy(matcher);
    if (!font) return false;
    const char *path = fonts->path(font);
    size_t collection = fonts->index(font);
    FT_Face face = NULL;
    bool added = false;
    if (collection > 0xffff || FT_New_Face(fonts->ft, path, (FT_Long)collection, &face)) goto done;
    int index = (int)collection;
    if (!nfb_android_instance(fonts, font, face, &index)) {
        ass_msg(lib, MSGL_WARN, "Android font variation cannot be represented by a named instance: %s", path);
        goto done;
    }
    if (index != (int)collection) {
        FT_Done_Face(face); face = NULL;
        if (FT_New_Face(fonts->ft, path, index, &face)) goto done;
    }
    ass_charmap_magic(lib, face);
    /* Tofu returned by AFontMatcher must not falsely satisfy fallback. */
    uint32_t scalar = length == 2 ? (((text[0] & 0x3ff) << 10) | (text[1] & 0x3ff)) + 0x10000 : text[0];
    if (!nfb_android_has_glyph(face, scalar)) goto done;
    char *name = (char *)logical_name;
    ASS_FontProviderMetaData meta = {
        .families = &name, .n_family = 1, .extended_family = name,
        .weight = fonts->weight(font), .style_flags = fonts->italic(font) ? FT_STYLE_FLAG_ITALIC : 0,
        .is_postscript = ass_face_is_postscript(face),
    };
    /* add_font takes ownership of face on both success and error. */
    added = ass_font_provider_add_font(fonts->provider, &meta, path, index, face);
    face = NULL;
done:
    if (face) FT_Done_Face(face);
    fonts->close(font);
    return added;
}

static void nfb_android_match(void *data, ASS_Library *lib,
                              ASS_FontProvider *provider, char *name)
{
    (void)provider;
    NfbAndroidFonts *fonts = data;
    for (unsigned italic = 0; italic < 2; italic++)
        for (unsigned bold = 0; bold < 2; bold++)
            nfb_android_add(fonts, lib, name, name, 'A', bold ? 700 : 400, italic);
}

static char *nfb_android_fallback(void *data, ASS_Library *lib,
                                 const char *family, uint32_t code)
{
    NfbAndroidFonts *fonts = data;
    /* Each fallback family describes the requested scalar, not a guessed title.
     * libass still tests the font's cmap and retains embedded font priority. */
    char *name = NULL;
    if (asprintf(&name, "nfb-android-%08x-%s", code, family) < 0) return NULL;
    bool added = false;
    for (unsigned italic = 0; italic < 2; italic++)
        for (unsigned bold = 0; bold < 2; bold++)
            added |= nfb_android_add(fonts, lib, family, name, code, bold ? 700 : 400, italic);
    if (!added) { free(name); return NULL; }
    return name;
}

static ASS_FontProvider *nfb_android_provider(ASS_Library *lib,
                                             ASS_FontSelector *selector,
                                             const char *config, FT_Library ft)
{
    (void)lib; (void)config;
    NfbAndroidFonts *fonts = calloc(1, sizeof(*fonts));
    if (!fonts) return NULL;
    fonts->ft = ft;
    /* SDK keeps API21 binary compatibility; app is API29+. Resolve at runtime
     * so missing APIs fall through to the retained upstream Fontconfig path. */
    fonts->handle = dlopen("libandroid.so", RTLD_NOW | RTLD_LOCAL);
    if (!fonts->handle) goto fail;
#define NFB_FONT_SYMBOL(field, symbol) do { \
    *(void **)(&fonts->field) = dlsym(fonts->handle, symbol); \
    if (!fonts->field) goto fail; \
} while (0)
    NFB_FONT_SYMBOL(create, "AFontMatcher_create");
    NFB_FONT_SYMBOL(destroy, "AFontMatcher_destroy");
    NFB_FONT_SYMBOL(style, "AFontMatcher_setStyle");
    NFB_FONT_SYMBOL(match, "AFontMatcher_match");
    NFB_FONT_SYMBOL(close, "AFont_close");
    NFB_FONT_SYMBOL(path, "AFont_getFontFilePath");
    NFB_FONT_SYMBOL(index, "AFont_getCollectionIndex");
    NFB_FONT_SYMBOL(axis_count, "AFont_getAxisCount");
    NFB_FONT_SYMBOL(axis_tag, "AFont_getAxisTag");
    NFB_FONT_SYMBOL(axis_value, "AFont_getAxisValue");
    NFB_FONT_SYMBOL(weight, "AFont_getWeight");
    NFB_FONT_SYMBOL(italic, "AFont_isItalic");
#undef NFB_FONT_SYMBOL
    ASS_FontProviderFuncs callbacks = {
        .check_glyph = nfb_android_has_glyph, .destroy_font = nfb_android_destroy_font,
        .destroy_provider = nfb_android_destroy, .match_fonts = nfb_android_match,
        .get_fallback = nfb_android_fallback,
    };
    fonts->provider = ass_font_provider_new(selector, &callbacks, fonts);
    if (fonts->provider) return fonts->provider;
fail:
    nfb_android_destroy(fonts);
    return NULL;
}
