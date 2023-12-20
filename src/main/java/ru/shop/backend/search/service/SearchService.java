package ru.shop.backend.search.service;

import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import ru.shop.backend.search.config.consts.TypeOfQuery;
import ru.shop.backend.search.dto.CatalogueElastic;
import ru.shop.backend.search.dto.SearchResultElastic;
import ru.shop.backend.search.model.Category;
import ru.shop.backend.search.dto.SearchResult;
import ru.shop.backend.search.dto.TypeHelpText;
import ru.shop.backend.search.model.*;
import ru.shop.backend.search.repository.ItemDbRepository;
import ru.shop.backend.search.repository.ItemRepository;

import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class SearchService {
    private final ItemRepository repo;
    private final ItemDbRepository repoDb;

    private final Pageable pageable = PageRequest.of(0, 150);
    private final Pageable pageableSmall = PageRequest.of(0, 10);

    public SearchResult getSearchResult(Integer regionId, String text) {
        List<CatalogueElastic> result = null;
        if (StringUtils.isNumeric(text)) {
            Integer itemId = repoDb.findBySku(text).stream().findFirst().orElse(null);
            if (itemId == null) {
                var catalogue = getByName(text);
                if (!CollectionUtils.isEmpty(catalogue)) {
                    result = catalogue;
                }
            } else {
                result = getByItemId(itemId.toString());
            }
        }
        if (result == null) {
            result = getAll(text);
        }
        List<Item> items = repoDb.findByIds(regionId,
                        result.stream()
                                .flatMap(category -> category.getItems().stream())
                                .map(ItemElastic::getItemId).collect(Collectors.toList())
                ).stream()
                .map(arr -> new Item(((BigInteger) arr[2]).intValue(), arr[1].toString(), arr[3].toString(), arr[4].toString(), ((BigInteger) arr[0]).intValue(), arr[5].toString()))
                .collect(Collectors.toList());
        String brand = "";
        if (!result.isEmpty())
            brand = result.get(0).getBrand().toLowerCase(Locale.ROOT);
        String finalBrand = brand;
        List<Category> categories = new ArrayList<>(repoDb.findCatsByIds(items.stream().map(Item::getItemId).collect(Collectors.toList())).stream()
                .collect(Collectors.toMap(arr -> arr[2].toString(), arr ->
                                new Category(arr[0].toString()
                                        , arr[1].toString()
                                        , "/cat/" + arr[2].toString() + (finalBrand.isEmpty() ? "" : "/brands/" + finalBrand)
                                        , "/cat/" + arr[3].toString(), arr[4] == null ? null : arr[4].toString())
                        , (existing, replacement) -> existing))
                .values());
        return new SearchResult(
                items,
                categories,
                !result.isEmpty() ? (List.of(new TypeHelpText(TypeOfQuery.SEE_ALSO,
                        ((result.get(0).getItems().get(0).getType() != null ? result.get(0).getItems().get(0).getType() : "") +
                                " " + (result.get(0).getBrand() != null ? result.get(0).getBrand() : "")).trim()))) : new ArrayList<>()
        );
    }

    public List<CatalogueElastic> getAll(String text) {
        return getAll(text, pageableSmall);
    }

    public List<CatalogueElastic> getAll(String text, Pageable pageable) {
        String type = "";
        List<ItemElastic> list;
        String brand = "", originalText = text;
        Long catalogueId = null;
        boolean needConvert = true;
        if(isContainErrorChar(text) || isContainErrorChar(convert(text))){
            if(isContainErrorChar(text)){
                text=convert(text);
            }
            needConvert=false;
        }
        if (text.contains(" ")) {
            for (String queryWord : text.split("\\s")) {
                list = repo.findAllByBrand(queryWord, pageable);
                if (list.isEmpty() && needConvert) {
                    list = repo.findAllByBrand(convert(text), pageable);
                }
                if (!list.isEmpty()) {
                    text = text.replace(queryWord, "").trim().replace("  ", " ");
                    brand = list.get(0).getBrand();
                    break;
                }

            }
        }
        list = repo.findAllByType(text, pageable);
        if (list.isEmpty() && needConvert) {
            list = repo.findAllByType(convert(text), pageable);
        }
        if (!list.isEmpty()) {
            type = (list.stream().map(ItemElastic::getType).min(Comparator.comparingInt(String::length)).get());
        } else {
            for (String queryWord : text.split("\\s")) {
                list = repo.findAllByType(queryWord, pageable);
                if (list.isEmpty() && needConvert) {
                    list = repo.findAllByType(convert(text), pageable);
                }
                if (!list.isEmpty()) {
                    text = text.replace(queryWord, "");
                    type = (list.stream().map(ItemElastic::getType).min(Comparator.comparingInt(String::length)).get());
                }
            }
        }
        if (brand.isEmpty()) {
            list = repo.findByCatalogue(text, pageable);
            if (list.isEmpty() && needConvert) {
                list = repo.findByCatalogue(convert(text), pageable);
            }
            if (!list.isEmpty()) {
                catalogueId = list.get(0).getCatalogueId();
            }
        }
        text = text.trim();
        if (text.isEmpty() && !brand.isEmpty())
            return Collections.singletonList(new CatalogueElastic(list.get(0).getCatalogue(), list.get(0).getCatalogueId(), null, brand));
        text += "?";
        if (brand.isEmpty()) {
            type += "?";
            if (catalogueId == null){
                list = repo.findAllByType(text, type, pageable);
                if (list.isEmpty()) {
                    list = repo.findAllByType(convert(text), type, pageable);
                }
            } else {
                list = repo.find(text, catalogueId, pageable);
                if (list.isEmpty()) {
                    list = repo.find(convert(text), catalogueId, pageable);
                }
            }

        } else {
            if (type.isEmpty()) {
                list = repo.findAllByBrand(text, brand, pageable);
                if (list.isEmpty()) {
                    list = repo.findAllByBrand(convert(text), brand, pageable);
                }
            } else {
                type += "?";
                list = repo.findAllByTypeAndBrand(text, brand, type, pageable);
                if (list.isEmpty()) {
                    list = repo.findAllByTypeAndBrand(convert(text), brand, type, pageable);
                }
            }
        }

        if (list.isEmpty()) {
            if (originalText.contains(" "))
                text = String.join(" ", text.split("\\s"));
            originalText += "?";
            list = repo.findAllNotStrong(originalText, pageable);
            if (list.isEmpty() && needConvert) {
                list = repo.findAllByTypeAndBrand(convert(originalText), brand, type, pageable);
            }
        }
        return get(list, text, brand);
    }

    private List<CatalogueElastic> get(List<ItemElastic> list, String name, String brand) {
        Map<String, List<ItemElastic>> map = new HashMap<>();
        AtomicReference<ItemElastic> searchedItem = new AtomicReference<>();
        String finalBrand = brand.isEmpty() ? null : brand;
        String replacedName = name.replace("?", "");
        list.forEach(
                i ->
                {
                    if (replacedName.equals(i.getName())) {
                        searchedItem.set(i);
                    }
                    if (replacedName.endsWith(i.getName()) && replacedName.startsWith(i.getType())) {
                        searchedItem.set(i);
                    }
                    if (!map.containsKey(i.getCatalogue())) {
                        map.put(i.getCatalogue(), new ArrayList<>());
                    }
                    map.get(i.getCatalogue()).add(i);
                }
        );
        if (searchedItem.get() != null) {
            ItemElastic i = searchedItem.get();
            return Collections.singletonList(new CatalogueElastic(i.getCatalogue(), i.getCatalogueId(), Collections.singletonList(i), finalBrand));
        }
        return map.keySet().stream().map(c ->
                new CatalogueElastic(c, map.get(c).get(0).getCatalogueId(), map.get(c), finalBrand)).collect(Collectors.toList());
    }

    public List<CatalogueElastic> getByName(String num) {
        List<ItemElastic> list = repo.findAllByName(num, pageable);
        return get(list, num, "");
    }

    public List<CatalogueElastic> getByItemId(String itemId) {
        var entity = repo.findByItemId(itemId);
        return Collections.singletonList(new CatalogueElastic(entity.getCatalogue(), entity.getCatalogueId(), List.of(entity), entity.getBrand()));
    }

    public static String convert(String message) {
        boolean result = message.matches(".*\\p{InCyrillic}.*");
        char[] ru = {'й', 'ц', 'у', 'к', 'е', 'н', 'г', 'ш', 'щ', 'з', 'х', 'ъ', 'ф', 'ы', 'в', 'а', 'п', 'р', 'о', 'л', 'д', 'ж', 'э', 'я', 'ч', 'с', 'м', 'и', 'т', 'ь', 'б', 'ю', '.',
                ' ', '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', '-'};
        char[] en = {'q', 'w', 'e', 'r', 't', 'y', 'u', 'i', 'o', 'p', '[', ']', 'a', 's', 'd', 'f', 'g', 'h', 'j', 'k', 'l', ';', '"', 'z', 'x', 'c', 'v', 'b', 'n', 'm', ',', '.', '/',
                ' ', '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', '-'};
        StringBuilder builder = new StringBuilder();

        if (result) {
            for (int i = 0; i < message.length(); i++) {
                for (int j = 0; j < ru.length; j++) {
                    if (message.charAt(i) == ru[j]) {
                        builder.append(en[j]);
                    }
                }
            }
        } else {
            for (int i = 0; i < message.length(); i++) {
                for (int j = 0; j < en.length; j++) {
                    if (message.charAt(i) == en[j]) {
                        builder.append(ru[j]);
                    }
                }
            }
        }
        return builder.toString();
    }

    private Boolean isContainErrorChar(String text) {
        if (text.contains("[") || text.contains("]") || text.contains("\"") || text.contains("/") || text.contains(";"))
            return true;
        return false;
    }

    public List<CatalogueElastic> getAllFull(String text) {
        return getAll(text, pageable);
    }

    public SearchResultElastic findBy(String text){
        if (StringUtils.isNumeric(text)) {
            Integer itemId = repoDb.findBySku(text).stream().findFirst().orElse(null);
            if (itemId == null) {
                var catalogue = getByName(text);
                if (!catalogue.isEmpty()) {
                    return new SearchResultElastic(catalogue);
                }
                return new SearchResultElastic(getAllFull(text));
            }
            try {
                return new SearchResultElastic(getByItemId(itemId.toString()));
            } catch (Exception e) {
            }
        }
        return new SearchResultElastic(getAllFull(text));
    }
}
