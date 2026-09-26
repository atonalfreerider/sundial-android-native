#!/usr/bin/env python3
"""Builds the Sundial Watch Face Format face from the instrument's rendered layers.

    generate.py ASSETS [--check REFERENCE]

ASSETS is the folder pulled from WatchFaceAssetsCapture (PNGs and layers.json). The script copies
the images into res/drawable-nodpi, makes the style icons and writes res/raw/watchface.xml.

The face moves the layers with Watch Face Format expressions that mirror the instrument's
astronomy: the Earth by the civil date, the planets by their heliocentric longitudes (mean
elements with the equation of centre), the Moon by its elongation from the Sun, the globe by
sidereal time. --check REFERENCE evaluates those expressions for every instant in
core/build/watchface-reference.json (written by WatchFaceReferenceTest) and fails if any
strays from the instrument.
"""
import argparse
import datetime
import hashlib
import json
import math
import os
import re
import shutil
import sys
from xml.sax.saxutils import quoteattr

from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
RES = os.path.join(os.path.dirname(HERE), 'src', 'main', 'res')

# ListOption ids follow CelestialStyle's order.
STYLES = ['void', 'crimson', 'blue', 'violet', 'bronze', 'brass']
PLANETS = ['mercury', 'venus', 'mars']
SEASONS = ['spring', 'summer', 'fall', 'winter']  # Zodiac.Season order

# ---------------------------------------------------------------------------------------------
# Expressions
# ---------------------------------------------------------------------------------------------


def num(value):
    """Ten significant digits, as a decimal the expression parser reads (no exponent)."""
    text = f'{value:.10g}'
    if 'e' in text:
        text = f'{value:.12f}'.rstrip('0').rstrip('.')
    return text if text not in ('', '-0') else '0'


def compact(expression):
    """Expressions are written with spaces for reading; the face does not need them."""
    return expression.replace(' ', '')


# The watch's offset from UTC. [TIMEZONE_OFFSET_DST] is text such as -7, +10 or +5:30; floor()
# reads the hours when they are alone, so the hours are cut from before any colon. (icuText("Z")
# would give ±hhmm, but the format cannot use it on the right of an operator.)
ZONE = '[TIMEZONE_OFFSET_DST]'
ZONE_LENGTH = f'textLength({ZONE})'
ZONE_HOURS = f'floor(subText({ZONE}, 0, (({ZONE_LENGTH} > 4) ? ({ZONE_LENGTH} - 3) : {ZONE_LENGTH})))'
ZONE_EXTRA_MINUTES = (f'(({ZONE_LENGTH} > 4) ? (((subText({ZONE}, 0, 1) == "-") ? (0 - 1) : 1)'
                      f' * floor(subText({ZONE}, {ZONE_LENGTH} - 2, {ZONE_LENGTH}))) : 0)')
LOCAL_MINUTES = '([HOUR_0_23] * 60 + [MINUTE])'
# UTC minutes after local midnight, exactly, and with only the offset's whole hours: a zone's
# extra half or three quarter hour moves the Moon less than half a degree, so the day count below
# (used hundreds of times) leaves it out; only sidereal time, which turns the globe, keeps it.
UTC_MINUTES = f'({LOCAL_MINUTES} - {ZONE_HOURS} * 60 - {ZONE_EXTRA_MINUTES})'
UTC_MINUTES_BY_HOURS = f'({LOCAL_MINUTES} - {ZONE_HOURS} * 60)'
# Days since J2000.0 (2000-01-01 12:00; UTC is close enough to TT at this scale), counted from
# the local date and time, which change once a minute. [UTC_TIMESTAMP] would be shorter but makes
# the watch redraw the face ten times a second. The day count is exact from 2000 to 2099.
D = f'(floor(365.25 * ([YEAR] - 2000) + 0.75) + [DAY_OF_YEAR] - 1.5 + {UTC_MINUTES_BY_HOURS} / 1440)'


def linear(c0, c1):
    """c0 + c1 · D, in degrees."""
    return f'({num(c0)} + {num(c1)} * {D})'


# JPL mean elements (Table 2a, J2000): a, e, L0, L rate / century, ϖ0, ϖ rate / century.
ELEMENTS = {
    'mercury': (0.38709843, 0.20563661, 252.25166724, 149472.67486623, 77.45771895, 0.15940013),
    'venus': (0.72332102, 0.00676399, 181.97970850, 58517.81560260, 131.76755713, 0.05679648),
    'earth': (1.00000018, 0.01673163, 100.46691572, 35999.37306329, 102.93005885, 0.31795260),
    'mars': (1.52371243, 0.09336511, -4.56813164, 19140.29934243, -23.91744784, 0.45223625),
}
# Terms of the equation of centre worth keeping: the rest move a body by well under a pixel.
CENTRE_TERMS = {'mercury': 2, 'venus': 1, 'earth': 1, 'mars': 2}
# General precession from J2000 to the equinox of date, per day.
PRECESSION_RATE = 1.3969713 / 36525


def mean_rate(body):
    return ELEMENTS[body][3] / 36525


def mean_anomaly(body, multiple=1):
    a, e, l0, l_rate, p0, p_rate = ELEMENTS[body]
    return f'rad({linear(multiple * (l0 - p0), multiple * (l_rate - p_rate) / 36525)})'


def centre(body):
    """The equation of centre (degrees): 2e sin M + 5/4 e² sin 2M."""
    e = ELEMENTS[body][1]
    coefficients = [math.degrees(2 * e - e ** 3 / 4), math.degrees(1.25 * e * e)]
    return ' + '.join(f'{num(c)} * sin({mean_anomaly(body, k + 1)})'
                      for k, c in enumerate(coefficients[:CENTRE_TERMS[body]]))


def helio_longitude(body, rate=0.0):
    """Heliocentric ecliptic longitude (degrees, J2000), turned rate degrees a day further."""
    l0 = ELEMENTS[body][2]
    return f'({linear(l0, mean_rate(body) + rate)} + {centre(body)})'


def helio_radius(body):
    a, e, *_ = ELEMENTS[body]
    if e < 0.05:
        return num(a)
    return f'({num(a)} * (1 - {num(e)} * cos({mean_anomaly(body)})))'


# The Sun from the Earth, referred to the equinox of date.
SUN_LONGITUDE = f'({linear(ELEMENTS["earth"][2] + 180, mean_rate("earth") + PRECESSION_RATE)} + {centre("earth")})'

# The instrument's lunar series (already of date), keeping the terms of 0.2° and more.
MOON_MEAN = (218.3164477, 13.17639648)
_ELONGATION = (297.8501921, 12.19074912)
_MOON_ANOMALY = (134.9633964, 13.06499295)


def _argument(*parts):
    return (sum(k * p[0] for k, p in parts), sum(k * p[1] for k, p in parts))


MOON_TERMS = ' + '.join(f'{num(c)} * sin(rad({linear(*arg)}))' for c, arg in [
    (6.289, _argument((1, _MOON_ANOMALY))),
    (1.274, _argument((2, _ELONGATION), (-1, _MOON_ANOMALY))),
    (0.658, _argument((2, _ELONGATION))),
    (0.214, _argument((2, _MOON_ANOMALY))),
])
MOON_LONGITUDE = f'({linear(*MOON_MEAN)} + {MOON_TERMS})'
# The Moon's elongation from the Sun, with the two mean motions folded into one term.
MOON_PHASE = (f'({linear(MOON_MEAN[0] - ELEMENTS["earth"][2] - 180, MOON_MEAN[1] - mean_rate("earth") - PRECESSION_RATE)}'
              f' + {MOON_TERMS} - ({centre("earth")}))')

LEAP = '((([YEAR] % 4 == 0) ? 1 : 0) - (([YEAR] % 100 == 0) ? 1 : 0) + (([YEAR] % 400 == 0) ? 1 : 0))'
DAY_FRACTION = '(([HOUR_0_23] + [MINUTE] / 60) / 24)'
YEAR_FRACTION = f'(([DAY_OF_YEAR] - 1 + {DAY_FRACTION}) / (365 + {LEAP}))'
JANUARY_FIRST = 90 - 10 * 360 / 365.256363004
MONTH_DAY = '([MONTH] * 100 + [DAY])'


def earth_angle():
    """Canvas angle of the Earth: the annual dial's civil date (DialGeometry.annualAngle)."""
    return f'({num(JANUARY_FIRST)} - 360 * {YEAR_FRACTION})'


def planet_angle(body):
    """Canvas angle of a planet (DialGeometry.eclipticAngle, north view)."""
    return f'(180 - {helio_longitude(body)})'


def turn(canvas_angle):
    """Clockwise rotation taking a part drawn at 12 o'clock to canvas_angle."""
    return f'({canvas_angle} + 90)'


def negate(expression):
    return f'(0 - {expression})'


# Canvas angle of the Moon around the Earth: the Earth view's lunar hand turned so that its Sun
# is the real one (DialGeometry.moonAngle plus the subdial's turn toward the Sun).
MOON_TURN = f'(180 - {MOON_PHASE})'
# Local time on the Earth's gear, which has noon toward the Sun and turns with it.
LOCAL_HOUR_TURN = '(0 - 15 * ([HOUR_0_23] + [MINUTE] / 60))'
# Weekday of 1 January (0 = Sunday; the format counts Sunday as 1), then the first Sunday's index.
JANUARY_FIRST_WEEKDAY = '(([DAY_OF_WEEK] - [DAY_OF_YEAR] + 371) % 7)'
FIRST_SUNDAY = f'((7 - {JANUARY_FIRST_WEEKDAY}) % 7)'
SUNDAYS_TURN = f'(0 - 360 * {FIRST_SUNDAY} / 365)'
# Greenwich sidereal time. 360.9856° a day is a whole turn plus 0.9856°, so the day count only
# enters through that 0.9856°, and the time of day through a quarter degree a minute.
DAY_COUNT = 'floor(365.25 * ([YEAR] - 2000) + 0.75)'
SIDEREAL = (f'({num(280.46061837 + 180 - 1.5 * 0.98564736629)} + {num(0.98564736629)} * ({DAY_COUNT} + [DAY_OF_YEAR])'
            f' + {num(0.25 + 0.98564736629 / 1440)} * {UTC_MINUTES})')


def globe_frame():
    return f'(floor(({SIDEREAL} % 360) / 15 + 0.5) % 24)'


def positive_degrees(expression):
    """Reduces an angle that is positive (every longitude here is, from 2000 on) to [0, 360)."""
    return f'(({expression}) % 360)'


def geocentric_longitude(body):
    """Tropical longitude of a planet from the Earth. In the frame turned to the Earth's
    heliocentric longitude, the Earth sits at (R, 0) and the planet at r(cos u, sin u), u being
    their difference in longitude; atan2 is made from atan."""
    r = helio_radius(body)
    earth_radius = helio_radius('earth')
    u = f'rad({linear(ELEMENTS[body][2] - ELEMENTS["earth"][2], mean_rate(body) - mean_rate("earth"))} + {centre(body)} - ({centre("earth")}))'
    x = f'({r} * cos({u}) - {earth_radius})'
    y = f'({r} * sin({u}))'
    earth = helio_longitude('earth', PRECESSION_RATE)
    return positive_degrees(f'{earth} + deg(atan({y} / {x})) + ((0 > {x}) ? 180 : 0)')


def sign_of(longitude):
    return f'floor({positive_degrees(longitude)} / 30)'


def hand_signs():
    return {
        'sun': sign_of(SUN_LONGITUDE),
        'moon': sign_of(MOON_LONGITUDE),
        **{body: f'floor({geocentric_longitude(body)} / 30)' for body in PLANETS},
    }


# The astrology setting, read where a setting nested in another would not follow it.
ASTROLOGY_ON = '[CONFIGURATION.astrology] == "TRUE"'
ASTROLOGY_OFF = '[CONFIGURATION.astrology] == "FALSE"'

# The zodiac sign of the civil date (Zodiac.signFor) and its season (Zodiac.seasonFor, north).
SIGN_STARTS = [(0, 321), (1, 420), (2, 521), (3, 621), (4, 723), (5, 823), (6, 923), (7, 1023),
               (8, 1122), (9, 1222), (10, 120), (11, 219)]


def date_sign_is(sign):
    ordered = sorted(SIGN_STARTS, key=lambda s: s[1])
    index = [s[0] for s in ordered].index(sign)
    start = ordered[index][1]
    end = ordered[(index + 1) % len(ordered)][1]
    if end > start:
        return f'({MONTH_DAY} >= {start}) && ({end} > {MONTH_DAY})'
    return f'({MONTH_DAY} >= {start}) || ({end} > {MONTH_DAY})'  # Capricorn spans the new year


SEASON_IS = {
    'spring': f'({MONTH_DAY} >= 321) && (621 > {MONTH_DAY})',
    'summer': f'({MONTH_DAY} >= 621) && (923 > {MONTH_DAY})',
    'fall': f'({MONTH_DAY} >= 923) && (1222 > {MONTH_DAY})',
}

# ---------------------------------------------------------------------------------------------
# A small evaluator for the expressions above, to check them against the instrument.
# ---------------------------------------------------------------------------------------------

TOKEN = re.compile(r'\s*(?:(\d+\.?\d*)|(\[[A-Z0-9_]+\])|([a-zA-Z]+)|"([^"]*)"|(&&|\|\||==|!=|>=|<=|[-+*/%()?:,<>!]))')


def evaluate(expression, sources):
    tokens = []
    position = 0
    while position < len(expression):
        match = TOKEN.match(expression, position)
        if not match or match.end() == position:
            if expression[position:].strip() == '':
                break
            raise ValueError(f'cannot read {expression[position:position + 20]!r}')
        position = match.end()
        number, source, name, string, operator = match.groups()
        if number is not None:
            tokens.append(('n', float(number)))
        elif source is not None:
            value = sources[source[1:-1]]
            tokens.append(('s', value) if isinstance(value, str) else ('n', float(value)))
        elif name is not None:
            tokens.append(('f', name))
        elif string is not None:
            tokens.append(('s', string))
        else:
            tokens.append(('o', operator))
    tokens.append(('end', None))
    index = [0]

    def peek():
        return tokens[index[0]]

    def take(expected=None):
        token = tokens[index[0]]
        if expected is not None and token != ('o', expected):
            raise ValueError(f'expected {expected}, got {token}')
        index[0] += 1
        return token

    functions = {
        'sin': math.sin, 'cos': math.cos, 'atan': math.atan, 'rad': math.radians, 'deg': math.degrees,
        # The format's floor() also reads a numeric string, and gives 0 for any other text.
        'floor': lambda x: math.floor(number(x)), 'fract': lambda x: x - math.floor(x), 'abs': abs,
        'subText': lambda text, start, end: text[int(start):int(end)],
        'textLength': lambda text: float(len(text)),
    }
    binary = [
        ['||'], ['&&'], ['==', '!='], ['>', '>=', '<', '<='], ['+', '-'], ['*', '/', '%'],
    ]

    def number(value):
        try:
            return float(value)
        except ValueError:
            return 0.0

    def error(message):
        raise ValueError(f'the watch cannot evaluate {message}')

    def apply(operator, a, b):
        return {
            '||': lambda: float(bool(a) or bool(b)), '&&': lambda: float(bool(a) and bool(b)),
            '==': lambda: float(a == b), '!=': lambda: float(a != b),
            '>': lambda: float(a > b), '>=': lambda: float(a >= b),
            '<': lambda: float(a < b), '<=': lambda: float(a <= b),
            '+': lambda: a + b, '-': lambda: a - b, '*': lambda: a * b, '/': lambda: a / b,
            '%': lambda: math.fmod(a, b) if a >= 0 else error(f'{a} % {b}'),
        }[operator]()

    def ternary():
        return level(0)

    def choice(condition):
        """On the watch ?: binds tighter than any binary operator: 0 > x ? a : b is
        0 > (x ? a : b). Conditions must therefore be parenthesised."""
        if peek() != ('o', '?'):
            return condition
        take('?')
        yes = unary()
        take(':')
        no = unary()
        return yes if condition else no

    def level(depth):
        if depth == len(binary):
            return unary()
        value = level(depth + 1)
        while peek()[0] == 'o' and peek()[1] in binary[depth]:
            operator = take()[1]
            value = apply(operator, value, level(depth + 1))
        return value

    def unary():
        if peek() == ('o', '-'):
            take()
            return -unary()
        if peek() == ('o', '!'):
            take()
            return float(not unary())
        return choice(primary())

    def primary():
        kind, value = take()
        if kind in ('n', 's'):
            return value
        if kind == 'f':
            take('(')
            arguments = [ternary()]
            while peek() == ('o', ','):
                take(',')
                arguments.append(ternary())
            take(')')
            result = functions[value](*arguments)
            return result if isinstance(result, str) else float(result)
        if (kind, value) == ('o', '('):
            inner = ternary()
            take(')')
            return inner
        raise ValueError(f'unexpected {value}')

    result = ternary()
    if peek()[0] != 'end':
        raise ValueError(f'trailing {peek()}')
    return result


def angle_error(a, b):
    return abs((a - b + 180) % 360 - 180)


def local_sources(epoch_millis, offset_minutes):
    """The watch's sources at an instant, in a zone offset_minutes from UTC."""
    local = datetime.datetime.fromtimestamp(epoch_millis / 1000, datetime.timezone.utc) + \
        datetime.timedelta(minutes=offset_minutes)
    hours, minutes = divmod(abs(offset_minutes), 60)
    zone = ('-' if offset_minutes < 0 else '+') + str(hours) + (f':{minutes:02d}' if minutes else '')
    return {
        'YEAR': local.year, 'MONTH': local.month, 'DAY': local.day,
        'DAY_OF_YEAR': local.timetuple().tm_yday, 'DAY_OF_WEEK': local.isoweekday() % 7 + 1,
        'HOUR_0_23': local.hour, 'MINUTE': local.minute,
        'TIMEZONE_OFFSET_DST': zone,
    }


def check(reference_path):
    rows = json.load(open(reference_path))
    worst = {}
    failures = []

    def compare(name, got, expected, tolerance, angular=True):
        error = angle_error(got, expected) if angular else abs(got - expected)
        worst[name] = max(worst.get(name, 0), error)
        if error > tolerance:
            failures.append(f'{name} at {row["epochMillis"]:.0f}: {got:.3f} vs {expected:.3f}')

    signs = hand_signs()
    for row in rows:
        sources = local_sources(row['epochMillis'], 0)

        def value(expression):
            return evaluate(expression, sources)

        # The day count must come out the same from any zone, to within the extra half or three
        # quarter hour some zones have; sidereal time must be exact in all of them.
        for offset in (-720, -420, -210, 0, 330, 345, 765, 840):
            zoned = local_sources(row['epochMillis'], offset)
            compare('days from zones', evaluate(D, zoned), row['epochMillis'] / 86400000 - 10957.5, 46 / 1440,
                    angular=False)
            compare('sidereal in zones', evaluate(SIDEREAL, zoned), row['sidereal'], 0.05)
        compare('earth', value(earth_angle()), row['earthAngle'], 0.02)
        for body in PLANETS:
            compare(body, value(planet_angle(body)), row[f'{body}Angle'], 0.8)
        compare('moon phase', value(MOON_PHASE), row['moonPhase'], 0.8)
        compare('sidereal', value(SIDEREAL), row['sidereal'], 0.05)
        frame = round(row['sidereal'] / 15) % 24
        boundary = abs((row['sidereal'] / 15) % 1 - 0.5) * 15
        if boundary > 1.0:
            compare('globe frame', value(globe_frame()), frame, 0, angular=False)
        compare('first sunday', value(FIRST_SUNDAY), row['firstSunday'], 0, angular=False)
        season = 3
        for index, name in enumerate(SEASONS[:3]):
            if value(SEASON_IS[name]):
                season = index
        compare('season', season, row['season'], 0, angular=False)
        lit = [sign for sign in range(12) if value(date_sign_is(sign))]
        compare('date sign', lit[0] if len(lit) == 1 else -1, row['dateSign'], 0, angular=False)
        compare('sun longitude', value(SUN_LONGITUDE), row['sunLongitude'], 0.05)
        compare('moon longitude', value(MOON_LONGITUDE), row['moonLongitude'], 0.8)
        for body in PLANETS:
            compare(f'{body} longitude', value(geocentric_longitude(body)), row[f'{body}Longitude'], 2.5)
        for hand, expression in signs.items():
            key = f'{hand}Longitude'
            # A sign may differ only when the body sits within the model's error of a cusp.
            cusp = abs((row[key] + 15) % 30 - 15)
            got = value(expression)
            if got != row[f'{hand}Sign'] and cusp > 2.5:
                failures.append(f'{hand} sign at {row["epochMillis"]:.0f}: {got} vs {row[hand + "Sign"]}')
    for name, error in worst.items():
        print(f'  {name:18s} worst error {error:.4f}')
    if failures:
        print('\n'.join(failures[:20]))
        sys.exit(f'{len(failures)} expression checks failed')
    print(f'All expressions agree with the instrument at {len(rows)} instants.')


# ---------------------------------------------------------------------------------------------
# Scene
# ---------------------------------------------------------------------------------------------


class Face:
    """The scene. Every moving part is one group with one transform; what differs by style,
    astrology mode or the always-on display is chosen inside it, so no expression repeats."""

    def __init__(self, layers):
        self.layers = layers
        self.images = layers['images']
        self.size = layers['size']
        self.cx = layers['cx']
        self.cy = layers['cy']
        self.r = layers['r']
        self.groups = 0
        # Images identical in several styles are stored once; the others refer to it.
        self.aliases = {}

    # Geometry of the reference pose the layers were drawn in.
    def orbit(self, body):
        return self.r * self.layers['orbits'][body]

    def top_point(self, body):
        return (self.cx, self.cy - self.orbit(body))

    @property
    def earth(self):
        return self.top_point('earth')

    @property
    def moon(self):
        x, y = self.earth
        return (x, y - self.r * self.layers['moonTrack'])

    # Elements.
    def image(self, name, extra=''):
        x, y, w, h = self.images[name]
        return (f'<PartImage x="{x}" y="{y}" width="{w}" height="{h}"{extra}>'
                f'<Image resource="{self.aliases.get(name, name)}"/></PartImage>')

    def group(self, name, children, pivot=None, angle=None, extra='', ambient_alpha=None):
        """A full-face group turned by the expression angle about pivot, the face centre by default."""
        # The runtime tells groups apart by name, so each gets its own.
        self.groups += 1
        attributes = f'name="{name}{self.groups}" x="0" y="0" width="{self.size}" height="{self.size}"{extra}'
        if angle:
            pivot = pivot or (self.cx, self.cy)
            attributes += f' pivotX="{num(pivot[0] / self.size)}" pivotY="{num(pivot[1] / self.size)}"'
        variant = (f'<Variant mode="AMBIENT" target="alpha" value="{ambient_alpha}"/>'
                   if ambient_alpha is not None else '')
        transform = f'<Transform target="angle" value={quoteattr(compact(angle))}/>' if angle else ''
        return f'<Group {attributes}>{variant}{transform}{"".join(children)}</Group>'

    def astrology(self, when_on, when_off):
        """Chooses by the astrology setting; either side may be empty. The watch does not always
        follow a setting nested inside another setting's option, so this reads the setting in a
        condition instead of nesting a BooleanConfiguration in the style lists."""
        on = self.group('on', when_on) if when_on else None
        off = self.group('off', when_off) if when_off else None
        if on:
            return self.condition([(ASTROLOGY_ON, on)], default=off)
        return self.condition([(ASTROLOGY_OFF, off)])

    def by_style(self, element_for):
        """element_for(style) is drawn for the chosen style."""
        options = ''.join(f'<ListOption id="{i}">{self.drawable(element_for(s))}</ListOption>'
                          for i, s in enumerate(STYLES))
        return f'<ListConfiguration id="style">{options}</ListConfiguration>'

    def slot(self, interactive, ambient=None, by_mode=False):
        """One image position: interactive(style[, mode]) names its image on the lit face and
        ambient([mode]) on the always-on face (None: nothing there). by_mode: the name also
        depends on the astrology setting ('z' on, 'a' off)."""
        def chosen(name_for):
            if by_mode:
                return self.astrology([self.image(name_for('z'))], [self.image(name_for('a'))])
            return self.image(name_for(None))

        lit = self.by_style(lambda s: chosen(lambda m: interactive(s, m)))
        if not ambient:
            return self.group('l', [lit], ambient_alpha=0)
        return self.group('s', [self.group('l', [lit], ambient_alpha=0),
                                self.group('a', [chosen(ambient)], extra=' alpha="0"', ambient_alpha=255)])

    def drawable(self, element):
        """Options and comparisons hold parts and groups, not a configuration itself."""
        return self.group('c', [element]) if element.startswith('<ListConfiguration') else element

    def condition(self, cases, default=None):
        """cases: [(expression, element)]; the first true one is drawn, else default."""
        cases = [(e, self.drawable(element)) for e, element in cases]
        default = self.drawable(default) if default else None
        expressions = ''.join(f'<Expression name="c{i}">{escape_text(compact(e))}</Expression>'
                              for i, (e, _) in enumerate(cases))
        compares = ''.join(f'<Compare expression="c{i}">{element}</Compare>'
                           for i, (_, element) in enumerate(cases))
        fallback = f'<Default>{default}</Default>' if default else ''
        return f'<Condition><Expressions>{expressions}</Expressions>{compares}{fallback}</Condition>'

    def at(self, radius, angle):
        return (f'({num(self.cx)} + {num(radius)} * cos(rad({angle})))',
                f'({num(self.cy)} + {num(radius)} * sin(rad({angle})))')

    def line(self, start, end, thickness, dash=None):
        dash_attributes = f' dashIntervals="{num(dash[0])} {num(dash[1])}"' if dash else ''
        transforms = ''.join(f'<Transform target="{t}" value={quoteattr(compact(v))}/>'
                             for t, v in zip(('startX', 'startY', 'endX', 'endY'), (*start, *end)))
        return (f'<Line startX="0" startY="0" endX="0" endY="0">'
                f'<Stroke color="#FFFFFFFF" thickness="{num(thickness)}"{dash_attributes}/>{transforms}</Line>')

    def fill(self, color, ambient_only=False):
        """The whole face in one colour: the source that a hand's mask cuts out."""
        hidden = ' alpha="0"' if ambient_only else ''
        variant = '<Variant mode="AMBIENT" target="alpha" value="255"/>' if ambient_only else ''
        return (f'<PartDraw x="0" y="0" width="{self.size}" height="{self.size}"{hidden}>{variant}'
                f'<Rectangle x="0" y="0" width="{self.size}" height="{self.size}"><Fill color="{color}"/></Rectangle>'
                f'</PartDraw>')

    # The instrument.
    def dial(self):
        """Sky, face, season band and annual dial; then the turning Sunday ticks and Earth spike."""
        base = self.condition(
            [(SEASON_IS[s], self.by_style(lambda style, s=s: self.image(f'base_{style}_{s}'))) for s in SEASONS[:3]],
            default=self.by_style(lambda style: self.image(f'base_{style}_winter')))
        return [
            self.group('base', [base], ambient_alpha=0),
            self.group('base always on', [self.astrology([self.image('amb_dial_z')], [self.image('amb_dial_a')])],
                       extra=' alpha="0"', ambient_alpha=255),
            self.group('sundays', [self.slot(lambda s, m: f'sundays_{s}', lambda m: 'amb_sundays')], angle=SUNDAYS_TURN),
            self.group('earth spike', [self.slot(lambda s, m: f'spike_{s}', lambda m: 'amb_spike')],
                       angle=turn(earth_angle())),
        ]

    def zodiac(self):
        """The zodiac ring with today's sign lit (its always-on form is part of amb_dial_z)."""
        lit = self.condition([(date_sign_is(k), self.slot(lambda s, m, k=k: f'zlit_{s}_{k}')) for k in range(12)])
        glyphs = [self.condition([(date_sign_is(k), self.slot(lambda s, m, k=k: f'zglyph_{s}_{k}_lit'))],
                                 default=self.slot(lambda s, m, k=k: f'zglyph_{s}_{k}')) for k in range(12)]
        return [self.slot(lambda s, m: f'zodiac_{s}'), lit, *glyphs]

    def zodiac_hands(self):
        """Lines from the Earth to each body and on to its sign, drawn once as a mask over the
        hand's colour in the chosen style, and each planet's sign marker seen through a disc
        turned to its sign."""
        styles = self.layers['handStyles']
        colors = {s: self.layers['styles'][s]['hands'] for s in STYLES}
        greys = {hand: grey_of(color) for hand, color in colors['void'].items()}
        signs = hand_signs()
        earth = self.at(self.orbit('earth'), earth_angle())
        # The Moon on the Earth's lunar track: the Earth's canvas angle turned by MOON_TURN.
        track = num(self.r * self.layers['moonTrack'])
        moon_angle = f'({earth_angle()} + 180 - {MOON_PHASE})'
        bodies = {
            'sun': (num(self.cx), num(self.cy)),
            'moon': (f'({earth[0]} + {track} * cos(rad({moon_angle})))',
                     f'({earth[1]} + {track} * sin(rad({moon_angle})))'),
            **{body: self.at(self.orbit(body), planet_angle(body)) for body in PLANETS},
        }
        hand_end = self.r * self.layers['zodiacHandEnd']
        aries = (self.cx + hand_end * math.cos(math.radians(165)), self.cy + hand_end * math.sin(math.radians(165)))
        parts = []
        for hand in ['mercury', 'venus', 'mars', 'sun', 'moon']:
            style = styles[hand]
            alpha = f'{style["alpha"]:02X}'
            sign_point = self.at(hand_end, f'(165 - 30 * {signs[hand]})')
            stroke = self.r * style['stroke']
            lines = (f'<PartDraw x="0" y="0" width="{self.size}" height="{self.size}" renderMode="MASK">'
                     + self.line(earth, bodies[hand], stroke)
                     + self.line(bodies[hand], sign_point, stroke, dash=(self.r * style['dash'], self.r * style['gap']))
                     + '</PartDraw>')
            parts.append(self.group(f'{hand} hand', [
                self.group('lit', [self.by_style(lambda s: self.fill(f'#{alpha}{colors[s][hand][3:]}'))], ambient_alpha=0),
                self.fill(f'#{alpha}{greys[hand][3:]}', ambient_only=True),
                lines,
            ]))
            if hand not in PLANETS:
                continue  # The Sun's and Moon's hands end at the ring without a marker.
            radius = 10
            disc = (f'<PartDraw x="0" y="0" width="{self.size}" height="{self.size}" pivotX="0.5" pivotY="0.5" '
                    f'renderMode="MASK"><Transform target="angle" value={quoteattr(compact(negate("30 * " + signs[hand])))}/>'
                    f'<Ellipse x="{num(aries[0] - radius)}" y="{num(aries[1] - radius)}" width="{2 * radius}" '
                    f'height="{2 * radius}"><Fill color="#FFFFFFFF"/></Ellipse></PartDraw>')
            parts.append(self.group(f'{hand} sign', [
                self.group('lit', [self.by_style(lambda s: self.image('markers', f' tintColor="{colors[s][hand]}"'))],
                           ambient_alpha=0),
                self.group('always on', [self.image('markers', f' tintColor="{greys[hand]}"')],
                           extra=' alpha="0"', ambient_alpha=255),
                disc,
            ]))
        return parts

    def planets(self):
        parts = []
        for body in ['mars', 'venus', 'mercury']:
            body_turn = turn(planet_angle(body))
            upright = self.group('upright', [self.slot(lambda s, m, b=body: f'planet_{s}_{m}_{b}',
                                                       lambda m, b=body: f'amb_planet_{m}_{b}', by_mode=True)],
                                 pivot=self.top_point(body), angle=negate(body_turn))
            parts.append(self.group(body, [
                self.slot(lambda s, m, b=body: f'orbit_{s}_{b}', lambda m, b=body: f'amb_orbit_{b}'), upright,
            ], angle=body_turn))
        return parts

    def earth_system(self):
        """The Earth's hand and subdial: gear, Moon, globe (or ⊕) and the local-hour tooth."""
        earth_turn = turn(earth_angle())
        moon = self.group('moon', [
            self.slot(lambda s, m: f'moonhand_{s}_{m}', lambda m: f'amb_moonhand_{m}', by_mode=True),
            self.group('upright', [self.slot(lambda s, m: f'moon_{s}_{m}', lambda m: f'amb_moon_{m}', by_mode=True)],
                       pivot=self.moon, angle=negate(MOON_TURN)),
        ], pivot=self.earth, angle=MOON_TURN)
        globe = self.group('globe', [self.condition(
            [(f'{globe_frame()} == {k}', self.image(f'globe_{k:02d}')) for k in range(24)])],
            pivot=self.earth, angle=negate(earth_turn), ambient_alpha=150)
        centre = self.astrology(
            [self.slot(lambda s, m: f'earth_{s}_z', lambda m: 'amb_earth_z'),
             self.group('upright', [self.slot(lambda s, m: f'earthsymbol_{s}', lambda m: 'amb_earthsymbol')],
                        pivot=self.earth, angle=negate(earth_turn))],
            [globe, self.slot(lambda s, m: f'earth_{s}_a', lambda m: 'amb_earth_a')])
        local_hour = self.group('local hour', [
            self.group('lit', [self.image('localhour')], ambient_alpha=0),
            self.group('always on', [self.image('amb_localhour')], extra=' alpha="0"', ambient_alpha=255),
        ], pivot=self.earth, angle=LOCAL_HOUR_TURN)
        return self.group('earth', [
            self.slot(lambda s, m: f'orbit_{s}_earth', lambda m: 'amb_orbit_earth'),
            self.slot(lambda s, m: f'subdial_{s}_{m}', lambda m: f'amb_subdial_{m}', by_mode=True),
            moon, centre, local_hour,
        ], angle=earth_turn)

    def time(self):
        """The instrument's title time: cy − r/2 baseline, 0.17 r tall, with its halo."""
        size = self.r * .17
        # The format centres text on its line box: ascent 0.917 em, descent 0.218 em.
        line_box = size * (0.917 + 0.218)
        baseline = self.cy - self.r * .5
        height = round(line_box + 8)
        top = round(baseline - size * 0.917 - (height - line_box) / 2)  # parts sit on whole pixels

        def text(hours, color, glow):
            shadow = f'<Shadow color="{glow}" radius="8" offsetX="0" offsetY="0">' if glow else ''
            return (f'<PartText x="{round(self.cx - 110)}" y="{top}" width="220" height="{height}">'
                    f'<Text align="CENTER"><Font family="franklin_condensed" size="{num(size)}" color="{color}">'
                    f'{shadow}<Template>%s:%s<Parameter expression="{hours}"/>'
                    f'<Parameter expression="[MINUTE_Z]"/></Template>{"</Shadow>" if glow else ""}</Font></Text>'
                    f'</PartText>')

        def clock(color, glow):
            return self.condition([('[IS_24_HOUR_MODE]', text('[HOUR_0_23_Z]', color, glow))],
                                  default=text('[HOUR_1_12]', color, glow))

        styles = self.layers['styles']
        return [
            self.group('time', [self.by_style(lambda s: clock(
                styles[s]['ink'], '#80FFF3D0' if styles[s]['brass'] else '#FF000000'))], ambient_alpha=0),
            self.group('time always on', [clock('#FFC8C8C8', None)], extra=' alpha="0"', ambient_alpha=255),
        ]

    def xml(self):
        style_options = ''.join(
            f'<ListOption id="{i}" displayName="style_{s}" icon="icon_{s}"/>' for i, s in enumerate(STYLES))
        scene = [
            *self.dial(),
            self.astrology([*self.zodiac(), *self.zodiac_hands()], None),
            *self.planets(),
            self.earth_system(),
            self.slot(lambda s, m: f'sun_{s}_{m}', lambda m: f'amb_sun_{m}', by_mode=True),
            *self.time(),
        ]
        return (
            '<?xml version="1.0" encoding="utf-8"?>\n'
            '<!-- Generated by watchface/tools/generate.py from the instrument\'s rendered layers. -->\n'
            f'<WatchFace width="{self.size}" height="{self.size}" clipShape="CIRCLE">'
            '<Metadata key="CLOCK_TYPE" value="DIGITAL"/>'
            '<Metadata key="PREVIEW_TIME" value="10:08:32"/>'
            '<UserConfigurations>'
            f'<ListConfiguration id="style" displayName="style_label" screenReaderText="style_label" '
            f'defaultValue="0">{style_options}</ListConfiguration>'
            '<BooleanConfiguration id="astrology" displayName="astrology_label" '
            'screenReaderText="astrology_label" defaultValue="FALSE"/>'
            '</UserConfigurations>'
            f'<Scene backgroundColor="#FF000000">{"".join(scene)}</Scene>'
            '</WatchFace>\n'
        )


def escape_text(text):
    return text.replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;')


def grey_of(color):
    """The always-on display's grey: the instrument's ColorMatrix (desaturate, 55%)."""
    r, g, b = int(color[3:5], 16), int(color[5:7], 16), int(color[7:9], 16)
    level = round((0.213 * r + 0.715 * g + 0.072 * b) * 0.55)
    return f'#FF{level:02X}{level:02X}{level:02X}'


def style_icon(assets, style, destination):
    """The style picker's swatch: the face in that style, round."""
    base = Image.open(os.path.join(assets, f'base_{style}_fall.png')).convert('RGBA').resize((96, 96), Image.LANCZOS)
    mask = Image.new('L', (96, 96), 0)
    ImageDraw.Draw(mask).ellipse((0, 0, 95, 95), fill=255)
    icon = Image.new('RGBA', (96, 96), (0, 0, 0, 0))
    icon.paste(base, (0, 0), mask)
    icon.save(destination, optimize=True)


def markers_image(assets, layers, destination):
    """All twelve sign markers on one transparent sheet, for the masked hand ends."""
    size = layers['size']
    sheet = Image.new('RGBA', (size, size), (0, 0, 0, 0))
    for k in range(12):
        x, y, w, h = layers['images'][f'marker_{k}']
        sheet.alpha_composite(Image.open(os.path.join(assets, f'marker_{k}.png')).convert('RGBA'), (x, y))
    box = sheet.getbbox()
    sheet.crop(box).save(destination, optimize=True)
    layers['images']['markers'] = [box[0], box[1], box[2] - box[0], box[3] - box[1]]


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('assets', nargs='?')
    parser.add_argument('--check', metavar='REFERENCE')
    args = parser.parse_args()
    if args.check:
        check(args.check)
    if not args.assets:
        return
    layers = json.load(open(os.path.join(args.assets, 'layers.json')))
    drawables = os.path.join(RES, 'drawable-nodpi')
    os.makedirs(drawables, exist_ok=True)
    # Everything here is generated except the picker preview, a screenshot of the face itself.
    for old in os.listdir(drawables):
        if old != 'preview.png':
            os.remove(os.path.join(drawables, old))
    face = Face(layers)
    stored = {}
    for name in sorted(layers['images']):
        if name.startswith('marker_'):
            continue
        source = os.path.join(args.assets, f'{name}.png')
        with open(source, 'rb') as png:
            key = (hashlib.sha256(png.read()).hexdigest(), tuple(layers['images'][name]))
        if key in stored:
            face.aliases[name] = stored[key]
            continue
        stored[key] = name
        shutil.copy(source, os.path.join(drawables, f'{name}.png'))
    markers_image(args.assets, layers, os.path.join(drawables, 'markers.png'))
    for style in STYLES:
        style_icon(args.assets, style, os.path.join(drawables, f'icon_{style}.png'))
    raw = os.path.join(RES, 'raw')
    os.makedirs(raw, exist_ok=True)
    xml = face.xml()
    with open(os.path.join(raw, 'watchface.xml'), 'w') as out:
        out.write(xml)
    print(f'watchface.xml: {len(xml) / 1024:.0f} KB, {len(os.listdir(drawables))} images')


if __name__ == '__main__':
    main()
