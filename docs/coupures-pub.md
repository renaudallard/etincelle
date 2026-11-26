# Coupures pub, chaîne par chaîne

Pour chaque chaîne, comment etincelle repère les coupures publicitaires, ce qu'il passe tout seul
selon le réglage, et où il reprend. État de la version 2026.11.44, au 26 novembre 2026.

## Le réglage « Passer les publicités automatiquement »

Une coupure qui n'est pas passée toute seule est toujours proposée par le bouton **Passer la pub**.

| Réglage | Passé tout seul |
|---|---|
| **Non** | rien : toujours le bouton |
| **Coupures signalées** | les coupures que la chaîne signale (groupes TF1 et France Télévisions) ; sur RMC et BFMTV, celles trouvées par la forme du flux ; les publicités que le serveur publicitaire place dans une coupure diffusée pendant qu'on regarde |
| **Signalées et presque sûres** | en plus, les coupures trouvées par les noirs entre les publicités, tout de suite, et celles trouvées par le logo et jugées sûres, 12 s après leur début. L'application prévient : peut couper la fin d'un générique ou quelques secondes d'émission. |

## Partout

- **Au direct, rien** : la fin de la coupure n'est pas encore diffusée. Il faut regarder en différé,
  d'au moins la durée de la coupure pour une coupure trouvée par le logo. La recherche par les noirs
  commence à 30 s du direct, celle par le logo à 60 s.
- **Regarder depuis le début** met en différé d'autant que le temps écoulé depuis le début du
  programme ; chaque coupure passée réduit ce retard.
- **Chromecast** : le récepteur ne passe rien tout seul ; il affiche **Passer la pub : OK** et la
  touche OK passe la coupure. Il trouve les coupures de la même façon, sauf par les publicités du
  serveur publicitaire, dont il n'a pas besoin.
- **Enregistrements** : coupures trouvées par les vignettes et les sous-titres, sûres seulement quand
  les deux sont d'accord ; reprise 32 s avant le retour du logo. Les replays n'ont pas de publicité.

## Les quatre façons de trouver une coupure

| Méthode | Comment | Reprise |
|---|---|---|
| **Signalée** | la chaîne marque chaque coupure dans le flux (SCTE-35) | exacte |
| **Forme RMC** | le flux est coupé en périodes : une partie, un jingle de moins de 10 s, une partie ; ou bien les publicités viennent du serveur publicitaire | exacte |
| **Noirs** | la demi-seconde de noir entre deux publicités, lue dans la taille des images du flux | à l'avant-dernier noir : la première publicité et une ou deux dernières passent |
| **Logo** | le logo de la chaîne disparaît des vignettes du service pendant la coupure | 32 s avant son retour, 24 s si la coupure est sûre |

## Chaîne par chaîne

Dans les tableaux, « Signalées » et « Presque sûres » désignent le réglage à partir duquel la coupure
est passée toute seule. Le retard est celui des vignettes du service sur le flux, mesuré le
26 novembre 2026.

### Chaînes qui signalent leurs coupures

Passées toutes seules dès **Coupures signalées**, reprise exacte.

| Chaîne | Trouvée par | Toute seule avec | Remarques |
|---|---|---|---|
| TF1, TMC, TFX, TF1 Séries Films | Signalée | Signalées | une période par coupure ; deux moitiés à 3 ou 4 s d'écart sont réunies |
| LCI | Signalée | Signalées | marques à l'intérieur d'une seule longue période |
| France 2, France 3, France 5 | Signalée | Signalées | |
| France 4, franceinfo, Arte | rien n'est cherché | | aucune coupure jamais vue |

### RMC et BFMTV

La forme du flux trouve environ un tiers des coupures RMC. Une coupure trouvée par la forme ou par le
serveur publicitaire compte comme signalée. Le logo prend le reste.

| Chaîne | Trouvée par | Toute seule avec | Retard | Remarques |
|---|---|---|---|---|
| RMC Story | Forme RMC, logo | Signalées ; Presque sûres pour un logo sûr | ≈ 6 s | les coupures de l'émission du matin sont d'une seule période, sans jingle : logo seulement |
| RMC Découverte | Forme RMC, logo | Signalées ; Presque sûres pour un logo sûr | non mesuré | entre deux épisodes, le logo est revenu 68 s après la reprise ; cette coupure n'était pas sûre : bouton seulement |
| RMC Life | Forme RMC, logo | Signalées ; Presque sûres pour un logo sûr | 7,4 s | |
| BFMTV | Forme RMC, logo | Signalées ; Presque sûres pour un logo sûr | ≈ 7,2 s | les longues coupures de la nuit, aux parties de 5 min 30 à 7 min, sont trouvées aussi |
| RMC Mécanic | logo | Presque sûres pour un logo sûr | non mesuré | ses périodes de 90 s sont des bandes-annonces de la chaîne, pas des coupures |

### Noirs entre les publicités, et logo

Une coupure trouvée par les noirs est passée tout de suite avec **Signalées et presque sûres**, du
deuxième noir à l'avant-dernier : la première et la dernière publicité passent toujours.
Un carton avec un seul noir ne coupe pas une coupure en deux, et une coupure
trouvée par le logo qui finit dans une coupure trouvée par les noirs va jusqu'au bout de celle-ci.

| Chaîne | Trouvée par | Toute seule avec | Retard | Remarques |
|---|---|---|---|---|
| M6 | noirs, logo | Presque sûres | 7,2 s | logo transparent, rarement sûr ; les cartons qui ouvrent et ferment les coupures aident |
| Gulli | noirs, logo | Presque sûres | 3,0 s | un dessin animé dont le logo va et vient n'est jamais sûr, à raison ; au changement de programme, le logo est revenu jusqu'à 136 s après la reprise : bouton seulement |
| Canal J | noirs, logo | Presque sûres | 2,6 s | |
| TIJI | noirs, logo | Presque sûres | 5,2 s | |
| Automoto | noirs, logo | Presque sûres | 4,7 s | |
| Toute l'Histoire | noirs, logo | Presque sûres | non mesuré | |
| AB 1 | noirs, logo | Presque sûres | 3,6 s | |
| Paris Première | noirs, logo | Presque sûres | 3,9 s | beaucoup de noirs manqués (première image sombre, noirs d'une seconde) ; une coupure coupée en deux par la marque d'une publicité dans un coin reste au bouton |

### Logo seulement

Une coupure jugée sûre est passée toute seule avec **Signalées et presque sûres**, 12 s après son
début. Les autres restent au bouton.

| Chaîne | Toute seule avec | Retard | Remarques |
|---|---|---|---|
| W9 | Presque sûres pour un logo sûr | non mesuré | ses cartons sont des barres animées : ils aident rarement à juger une coupure sûre |
| 6ter | Presque sûres pour un logo sûr | 3,4 s | |
| CSTAR | Presque sûres pour un logo sûr | ≈ 3,0 s | |
| CNEWS | Presque sûres pour un logo sûr | ≈ 2,6 s | une coupure sûre peut reprendre dans le générique d'ouverture de l'émission, qui ne compte pas comme l'émission |
| L'Équipe | Presque sûres pour un logo sûr | ≈ 3,8 s | |
| T18 | Presque sûres pour un logo sûr | 8,2 s | règle à part pour juger une coupure sûre : son logo s'en va juste avant la coupure |
| NOVO19 | Presque sûres pour un logo sûr | 2,7 s | |
| Téva | Presque sûres pour un logo sûr | 2,8 s | |
| toutes les autres | Presque sûres pour un logo sûr | non mesuré | logo par défaut, chaînes non étudiées |

## Où reprend une coupure trouvée par le logo

Elle reprend 32 s avant le retour du logo, 24 s quand elle est sûre. Les vignettes du service sont en
retard sur le flux, de 2,6 à 8,2 s selon la chaîne : il reste donc en pratique 27 à 40 s de
publicités avant l'émission (27 à 32 s pour une coupure sûre, 35 à 40 s pour les autres).

Cette marge couvre une émission qui reprend jusqu'à 20 s avant son logo, ce qui a été vérifié sur
210 coupures sûres en novembre 2026. Quand le logo revient bien plus tard, au changement de
programme, la coupure n'est pas sûre et reste au bouton.

## Limites connues

- Au direct, aucune coupure ne peut être passée.
- Sur Paris Première, beaucoup de coupures restent au bouton et reprennent 32 s avant l'émission.
- Au changement de programme, le logo peut revenir une minute ou plus après la reprise ; le bouton
  peut alors reprendre dans le programme suivant.
- L'émission du matin de RMC Story n'est trouvée que par le logo.
- Avec les noirs, la première et la dernière publicité d'une coupure passent toujours.

## Les règles en détail

### Coupures trouvées d'après le logo

- Les vignettes que le service garde de chaque chaîne montrent un logo dans un coin pendant
  l'émission : celui de la chaîne, en haut, de préférence à celui d'une émission, en bas, dont la
  présence, quand elle ressort nettement, suffit toutefois à reconnaître l'émission. Il disparaît
  pendant la publicité.
- C'est une estimation : une publicité qui porte quelque chose à elle dans le coin d'un de ces logos
  peut écourter la coupure ou la faire manquer.
- Quand des publicités gardent le logo de la chaîne visible la moitié du temps, comme les jouets sur
  Gulli, la disparition du logo de l'émission tout du long suffit, si celui de la chaîne est bien
  visible avant et après ; jamais au-delà de son retour (une scène de l'émission sans aucun des deux
  logos, juste avant ou juste après, n'en fait pas partie), et jamais sautée d'office.
- Une bande-annonce qui porte le pictogramme de la chaîne au milieu des publicités, comme sur Paris
  Première, ne coupe pas la coupure en deux : le logo du bas ne compte pour l'émission qu'au-delà de
  40 secondes. Les dernières publicités restent dans la coupure tant que rien entre elles ne
  ressemble à l'émission (les deux logos à la fois, ou celui de l'émission pendant vingt secondes).
- Une publicité qui montre sa marque dans le coin du logo de la chaîne, celui de l'émission absent,
  comme sur M6 dont le logo est pâle, ne compte pas pour l'émission jusqu'à 48 secondes, même entre
  une coupure et quelques minutes de publicités trop courtes pour en faire une à elles seules.
- Dans l'heure qui précède le programme du soir, où publicités et bandes-annonces sont si nombreuses
  qu'aucun logo ne ressort plus, le logo de la chaîne trouvé plus tôt sert encore, même lors d'un
  autre visionnage depuis le lancement de l'application, ou à défaut celui qui ressort dans les deux
  heures précédentes, cherché une fois par visionnage, tant qu'il se voit sur une bonne part des
  vignettes.
- Une coupure trouvée ainsi dure de deux minutes et demie à vingt minutes : sur M6, publicités et
  bandes-annonces occupent dix-huit minutes et demie entre le journal du soir et la série qui suit.
- Sur les chaînes qui signalent leurs coupures (groupe TF1, LCI, France Télévisions), rien d'autre
  n'est cherché : bandes-annonces et parrainages ne se distinguent pas sûrement du début de
  l'émission.

### Coupures presque sûres

- Avec **Signalées et presque sûres**, une coupure trouvée d'après le logo dont rien ne laisse douter
  est sautée douze secondes après son début : d'un seul tenant, que le logo d'une émission ne réduit
  pas, ou de douze secondes au plus à chaque bord, et le logo de la chaîne bien visible avant et
  après. T18 retire le sien juste avant la fin de l'émission et le remet une douzaine de secondes
  après sa reprise : sur les vignettes de novembre 2026, cette tolérance a donné 22 coupures de plus
  sur 11 chaînes, toutes sautées dans la publicité.
- Jamais d'après le seul logo d'une émission, qui part avec elle : la coupure courrait alors de la fin
  d'une émission jusque dans le début de la suivante.
- Sur près de 18 heures de vignettes de ces chaînes, en novembre 2026, c'était le cas de 133 coupures
  sur 313 (un peu moins depuis, la règle étant devenue plus stricte) ; toutes s'arrêtaient dans la
  publicité, les bandes-annonces ou les génériques de la chaîne, mais certaines commençaient pendant
  le générique de fin de l'émission, et une huit secondes avant la fin d'un bulletin météo.
- Au direct, une coupure presque sûre s'arrête 24 secondes seulement avant le retour du logo : après
  les 210 coupures presque sûres de ces vignettes, l'émission elle-même, son générique de début à
  part, ne reprenait jamais plus de 20 secondes avant lui.
- Une coupure trouvée d'après le logo de la chaîne est aussi presque sûre, même découpée, quand ses
  deux bords sont marqués par une image revue dans la coupure (le carton « Publicité » de la chaîne,
  son jingle, le générique de reprise de l'émission, une publicité passée au début et à la fin) et
  que le logo de la chaîne n'y reparaît jamais plus de 36 secondes d'affilée : sur les mêmes
  vignettes, 33 coupures de plus, dont aucune ne commençait avant la fin de l'émission ni ne
  s'arrêtait après sa reprise.

### Noirs entre les publicités

- M6, Gulli, Canal J, TIJI, Paris Première, Automoto, Toute l'Histoire et AB 1 séparent deux
  publicités par une demi-seconde de noir. L'image du flux est chiffrée, mais pas la taille de chaque
  image, et un noir en fait une douzaine de quelques octets. L'en-tête de chaque segment de la vidéo
  suffit.
- Il est lu dans trois qualités de la vidéo (270, 540 et 1080 lignes), chacune avec ses propres
  seuils, soit environ 11 Mo par heure : chacune manque des noirs que les autres trouvent (un noir
  de quelques octets de trop, une publicité qui commence sur une image sombre), et un noir trouvé
  dans l'une compte, une seule fois. La qualité 432 lignes n'est pas lue : elle prenait des images
  de dessins animés de Canal J pour des noirs.
- Sur deux heures de M6, Gulli et Paris Première et quatre heures de Canal J, TIJI, Automoto,
  Toute l'Histoire et AB 1, en novembre 2026, les trois qualités passaient 45 % de publicité de plus
  que la plus basse seule ; tout ce qu'elles ajoutaient était de la publicité ou des bandes-annonces.
- Une coupure compte au moins six noirs sur une minute et demie ou plus, jamais à plus d'une minute
  l'un de l'autre, ou d'une minute quarante entre deux groupes d'au moins trois : un noir manqué
  laisse deux publicités entre deux autres, mais un ou deux noirs d'un dessin animé juste avant la
  coupure ne s'y joignent pas. Un ou deux noirs entre deux groupes, à une minute quarante au plus de
  chacun (le carton « Publicité » de M6 parmi des bandes-annonces, le parrainage de Gulli), ne la
  coupent pas non plus.
- Elle va du deuxième noir à l'avant-dernier, qu'un noir égaré dans l'émission juste avant ou après la
  coupure laisse encore dans la publicité ; **Signalées et presque sûres** la saute dès le deuxième.
- Une coupure trouvée d'après le logo qui commence avant elle et s'arrête dedans mène jusqu'à sa fin.
- Sur quatre heures de chacune de ces chaînes, en novembre 2026, elle trouvait 8 coupures de M6 et 7
  de Gulli, et sur quatre heures des six autres 35 coupures ; toutes celles dont les vignettes
  restaient (33) commençaient et s'arrêtaient dans la publicité, de quelques secondes à une minute et
  demie avant la reprise de l'émission.
- Les autres chaînes n'en ont pas, ou trop peu pour faire une coupure (W9, 6ter, TF1, France
  Télévisions, groupe RMC, T18, NOVO19…) ; les images figées des dessins animés de Gulli en imitent
  quelques-uns, jamais assez pour faire une coupure.

### Enregistrements

- Un enregistrement ne signale rien : ses coupures sont cherchées à l'ouverture, d'après ses vignettes
  de la même façon et ses sous-titres (une émission parle pendant des minutes, une publicité quelques
  secondes).
- Le bouton apparaît une dizaine de secondes après le début de la lecture sur un réseau domestique, le
  temps de la recherche (une quinzaine de Mo par heure d'enregistrement, soit un centième de ce que
  demande sa lecture), refaite cinq minutes plus tard, puis de moins en moins souvent, si le réseau
  l'a interrompue.
- Seul **Signalées et presque sûres** y saute une coupure : presque sûre d'après les vignettes, et
  dont les sous-titres, quand ils permettent d'en juger, confirment les deux bords.
